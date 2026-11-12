/* Neural upscaling service: runs realesr-general-x4v3 (SRVGGNetCompact, BSD-3, ~4.8MB)
   with ONNX Runtime Web (WebGPU, falling back to WASM) entirely on-device.
   The model is downloaded once and cached; frames arrive over MessagePorts. */
import * as ort from '../../node_modules/onnxruntime-web/dist/ort.webgpu.min.mjs'

const MODEL_URL = 'https://huggingface.co/CoderViking/realesr-general-x4v3-onnx/resolve/main/realesr-general-x4v3.onnx'
const CACHE_NAME = 'min-upscale-models-v1'

ort.env.wasm.wasmPaths = 'min://app/node_modules/onnxruntime-web/dist/'
ort.env.wasm.numThreads = 1 // no cross-origin isolation => no SharedArrayBuffer

let sessionPromise = null
let backend = null
const subscribers = new Set()

function broadcast (msg) {
  subscribers.forEach(p => p.postMessage(msg))
}

async function loadModelBytes () {
  const cache = await caches.open(CACHE_NAME)
  let res = await cache.match(MODEL_URL)
  if (!res) {
    broadcast({ type: 'status', state: 'downloading' })
    const fetched = await fetch(MODEL_URL, { credentials: 'omit' })
    if (!fetched.ok) throw new Error('model download failed: ' + fetched.status)
    await cache.put(MODEL_URL, fetched.clone())
    res = fetched
  }
  return new Uint8Array(await res.arrayBuffer())
}

function getSession () {
  if (!sessionPromise) {
    sessionPromise = (async () => {
      const bytes = await loadModelBytes()
      broadcast({ type: 'status', state: 'loading' })
      // probe first: a failed WebGPU init leaves the shared wasm runtime unusable
      let hasGpu = false
      try { hasGpu = !!(navigator.gpu && await navigator.gpu.requestAdapter()) } catch (e) { /* no adapter */ }
      const eps = hasGpu ? ['webgpu', 'wasm'] : ['wasm']
      for (const ep of eps) {
        try {
          const s = await ort.InferenceSession.create(bytes, { executionProviders: [ep], graphOptimizationLevel: 'all' })
          backend = ep
          broadcast({ type: 'status', state: 'ready', backend })
          return s
        } catch (e) {
          console.warn('upscaler: backend failed', ep, e)
        }
      }
      throw new Error('no usable ONNX backend')
    })()
    sessionPromise.catch(e => { sessionPromise = null; broadcast({ type: 'status', state: 'error', message: String(e) }) })
  }
  return sessionPromise
}

let queue = Promise.resolve()

async function upscale (msg) {
  const session = await getSession()
  const { w, h } = msg
  const px = w * h
  const rgba = new Uint8ClampedArray(msg.data)
  const input = new Float32Array(3 * px)
  for (let i = 0, j = 0; i < px; i++, j += 4) {
    input[i] = rgba[j] / 255
    input[px + i] = rgba[j + 1] / 255
    input[2 * px + i] = rgba[j + 2] / 255
  }
  console.log('upscale: run start', w, h, backend)
  const t0 = performance.now()
  const out = await session.run({ [session.inputNames[0]]: new ort.Tensor('float32', input, [1, 3, h, w]) })
  const ms = performance.now() - t0
  const o = out[session.outputNames[0]]
  const ow = w * 4; const oh = h * 4; const opx = ow * oh
  const d = o.data
  const res = new Uint8ClampedArray(opx * 4)
  for (let i = 0, j = 0; i < opx; i++, j += 4) {
    res[j] = d[i] * 255 + 0.5
    res[j + 1] = d[opx + i] * 255 + 0.5
    res[j + 2] = d[2 * opx + i] * 255 + 0.5
    res[j + 3] = 255
  }
  o.dispose && o.dispose()
  return { ms, w: ow, h: oh, data: res.buffer }
}

window.addEventListener('message', function (e) {
  if (e.source !== window || e.data !== 'upscale-session-create') return
  const port = e.ports[0]
  subscribers.add(port)
  port.onmessage = function (ev) {
    const msg = ev.data
    if (msg.type === 'warmup') {
      getSession().catch(() => {})
    } else if (msg.type === 'close') {
      subscribers.delete(port); port.close()
    } else if (msg.type === 'frame') {
      console.log('upscale: frame received', msg.w, msg.h)
      queue = queue.then(() => upscale(msg)).then(r => {
        port.postMessage({ type: 'result', id: msg.id, mediaTime: msg.mediaTime, ...r }, [r.data])
      }).catch(err => {
        port.postMessage({ type: 'error', id: msg.id, message: String(err) })
      })
    }
  }
  port.start()
})
