// Shared helpers for the engine tests: self-contained (no files outside this folder), each test exits non-zero on failure.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
// ENGINE_FILE=... runs the tests against another copy of the engine (e.g. an older one, to see a test catch its fault)
const ENGINE = fs.readFileSync(process.env.ENGINE_FILE || path.resolve(__dirname, '../android/app/src/main/assets/engine.js'), 'utf8')
const ORT_DIR = path.join(__dirname, 'node_modules/onnxruntime-web/dist')
const FAKE_X4 = path.join(__dirname, 'fixtures/fake_x4.onnx') // 4x nearest-neighbour "model" (make_fake_model.py)
const GPU_ARGS = ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required']

let failures = 0
function check (name, ok, detail) {
  console.log((ok ? '  ok   ' : '  FAIL ') + name + (detail !== undefined ? '  ' + (typeof detail === 'string' ? detail : JSON.stringify(detail)) : ''))
  if (!ok) failures++
}
async function launch () { return chromium.launch({ args: GPU_ARGS }) }
/** run a test body; prints the result and sets the exit code */
function run (title, body) {
  console.log(title)
  // exit explicitly: a failed step can leave the browser open, which would keep node alive
  body().then(() => process.exit(failures ? 1 : 0), e => { console.log('  FAIL crashed: ' + (e && e.stack || e)); process.exit(1) })
}
/** in-page: a <video> fed by a canvas stream (no media file needed). page.evaluate passes one argument: the video id,
 *  or { id, w, h, split (red top / blue bottom), duration (fake, seconds; a stream is otherwise endless), play (false = left paused) } */
function canvasVideo (a) {
  const o = Object.assign({ w: 160, h: 90, split: false, duration: 0, play: true }, typeof a === 'string' ? { id: a } : a)
  const v = document.getElementById(o.id); const c = document.createElement('canvas'); c.width = o.w; c.height = o.h
  const x = c.getContext('2d'); let t = 0
  setInterval(() => {
    t++
    if (o.split) { x.fillStyle = '#f00'; x.fillRect(0, 0, o.w, o.h / 2); x.fillStyle = '#00f'; x.fillRect(0, o.h / 2, o.w, o.h / 2) } else { x.fillStyle = '#48c'; x.fillRect(0, 0, o.w, o.h) }
    x.fillStyle = '#0f0'; x.fillRect(t % (o.w - 4), 0, 4, 4) // something moves, so the stream keeps sending frames
  }, 33)
  if (o.duration) Object.defineProperty(v, 'duration', { configurable: true, get: () => o.duration })
  v.muted = true; v.srcObject = c.captureStream(30)
  return o.play ? v.play().catch(() => {}) : null // the engine may pause it at once (hand-over)
}
/** in-page: a <video> playing a real (looping) webm file, recorded here from a canvas: behaves like a site's video file
 *  (unlike a live stream, a paused file video sends no more frames). One argument: id, or { id, seconds, play, color }.
 *  Called again on the same video it gives the element a new source (another clip). */
async function fileVideo (a) {
  const o = Object.assign({ seconds: 2, play: false, color: '#48c' }, typeof a === 'string' ? { id: a } : a)
  const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); let t = 0
  const iv = setInterval(() => { t++; x.fillStyle = o.color; x.fillRect(0, 0, 160, 90); x.fillStyle = '#0f0'; x.fillRect(t % 150, 0, 6, 6) }, 33)
  const rec = new MediaRecorder(c.captureStream(30), { mimeType: 'video/webm' }); const parts = []
  rec.ondataavailable = e => parts.push(e.data); rec.start()
  await new Promise(r => setTimeout(r, o.seconds * 1000)); rec.stop(); await new Promise(r => { rec.onstop = r }); clearInterval(iv)
  const v = document.getElementById(o.id); v.muted = true; v.loop = true; v.preload = 'auto'
  v.src = URL.createObjectURL(new Blob(parts, { type: 'video/webm' }))
  await new Promise(r => { if (v.readyState >= 2) r(); else v.addEventListener('loadeddata', r, { once: true }) })
  if (o.play) await v.play().catch(() => {})
}
/** in-page: records a short webm clip of one colour and returns it as base64 (to serve it from another "site") */
async function webmBase64 (a) {
  const o = Object.assign({ seconds: 2, color: '#48c' }, a)
  const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); let t = 0
  const iv = setInterval(() => { t++; x.fillStyle = o.color; x.fillRect(0, 0, 160, 90); x.fillStyle = '#0f0'; x.fillRect(t % 150, 0, 6, 6) }, 33)
  const rec = new MediaRecorder(c.captureStream(30), { mimeType: 'video/webm' }); const parts = []
  rec.ondataavailable = e => parts.push(e.data); rec.start()
  await new Promise(r => setTimeout(r, o.seconds * 1000)); rec.stop(); await new Promise(r => { rec.onstop = r }); clearInterval(iv)
  const u = new Uint8Array(await new Blob(parts).arrayBuffer()); let s = ''
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000))
  return btoa(s)
}
/** in-page (init script): ONNX Runtime stand-in. Its "model" fills the 4x output with the input's average colour, so the
 *  overlay shows which frame went through it; window.__runs / __runAt count the runs. Argument: delay of each run, ms. */
function ortStub (delay) {
  window.__runs = 0; window.__runAt = []
  window.ort = {
    env: { wasm: {} },
    Tensor: function (type, data, dims) { this.type = type; this.data = data; this.dims = dims },
    InferenceSession: {
      create: async () => ({
        inputNames: ['x'],
        outputNames: ['y'],
        run: async (f) => {
          window.__runs++; window.__runAt.push(Math.round(performance.now()))
          const h = f.x.dims[2]; const w = f.x.dims[3]; const n = w * h; const N = 16 * n; const o = new Float32Array(3 * N)
          for (let c = 0; c < 3; c++) { let s = 0; for (let i = 0; i < n; i++) s += f.x.data[c * n + i]; o.fill(s / n, c * N, (c + 1) * N) }
          if (delay) await new Promise(r => setTimeout(r, delay))
          return { y: { data: o } }
        }
      })
    }
  }
}
/** serve the app's asset host: ONNX Runtime from node_modules, the fake model for every model path */
async function routeAssets (page) {
  await page.route('https://appassets.min.local/**', route => {
    const q = new URL(route.request().url()).pathname
    const f = q.startsWith('/ort/') ? path.join(ORT_DIR, q.slice(5)) : q.startsWith('/model/') ? FAKE_X4 : null
    if (!f || !fs.existsSync(f)) return route.fulfill({ status: 404 })
    route.fulfill({ body: fs.readFileSync(f), headers: { 'access-control-allow-origin': '*', 'content-type': f.endsWith('.wasm') ? 'application/wasm' : f.endsWith('.onnx') ? 'application/octet-stream' : 'text/javascript' } })
  })
}
const ortScript = () => fs.readFileSync(path.join(ORT_DIR, 'ort.wasm.min.js'), 'utf8')
module.exports = { ENGINE, ORT_DIR, FAKE_X4, launch, run, check, canvasVideo, fileVideo, webmBase64, ortStub, routeAssets, ortScript, fs, path }
