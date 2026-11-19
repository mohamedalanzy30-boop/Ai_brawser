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
 *  or { id, w, h, split (red top / blue bottom), duration (fake, seconds; a stream is otherwise endless) } */
function canvasVideo (a) {
  const o = Object.assign({ w: 160, h: 90, split: false, duration: 0 }, typeof a === 'string' ? { id: a } : a)
  const v = document.getElementById(o.id); const c = document.createElement('canvas'); c.width = o.w; c.height = o.h
  const x = c.getContext('2d'); let t = 0
  setInterval(() => {
    t++
    if (o.split) { x.fillStyle = '#f00'; x.fillRect(0, 0, o.w, o.h / 2); x.fillStyle = '#00f'; x.fillRect(0, o.h / 2, o.w, o.h / 2) } else { x.fillStyle = '#48c'; x.fillRect(0, 0, o.w, o.h) }
    x.fillStyle = '#0f0'; x.fillRect(t % (o.w - 4), 0, 4, 4) // something moves, so the stream keeps sending frames
  }, 33)
  if (o.duration) Object.defineProperty(v, 'duration', { configurable: true, get: () => o.duration })
  v.muted = true; v.srcObject = c.captureStream(30); return v.play().catch(() => {}) // the engine may pause it at once (hand-over)
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
module.exports = { ENGINE, ORT_DIR, FAKE_X4, launch, run, check, canvasVideo, routeAssets, ortScript, fs, path }
