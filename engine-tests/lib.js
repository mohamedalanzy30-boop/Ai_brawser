// Shared helpers for the engine tests: browser launch, fixtures, assertions, a tiny fake x4 ONNX model.
const fs = require('fs'); const path = require('path'); const { execSync } = require('child_process')

function load (name) { // local devDependency first, then the globally installed copy
  try { return require(name) } catch (e) {
    try { return require(path.join(execSync('npm root -g').toString().trim(), name)) } catch (e2) { throw new Error(name + ' not found: run "npm install" in engine-tests/') }
  }
}
const { chromium } = load('playwright')

const ENGINE_PATH = process.env.ENGINE_JS ? path.resolve(process.env.ENGINE_JS) : path.resolve(__dirname, '../android/app/src/main/assets/engine.js') // ENGINE_JS: test another copy
const ENGINE = fs.readFileSync(ENGINE_PATH, 'utf8')
const GL_ARGS = ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required']
const fixture = (name) => fs.readFileSync(path.join(__dirname, 'fixtures', name))

function ortDir () {
  let main
  try { main = require.resolve('onnxruntime-web') } catch (e) { throw new Error('onnxruntime-web not found: run "npm install" in engine-tests/') }
  return path.dirname(main) // .../onnxruntime-web/dist
}

/* Minimal protobuf writer, enough for one ONNX ModelProto. */
const varint = (n) => { const b = []; n = BigInt(n); do { let x = Number(n & 0x7fn); n >>= 7n; if (n) x |= 0x80; b.push(x) } while (n); return Buffer.from(b) }
const key = (field, wire) => varint((field << 3) | wire)
const bytesF = (field, buf) => Buffer.concat([key(field, 2), varint(buf.length), buf])
const strF = (field, s) => bytesF(field, Buffer.from(s, 'utf8'))
const intF = (field, n) => Buffer.concat([key(field, 0), varint(n)])
/** ONNX model: 'input' [1,3,H,W] float -> 'output' [1,3,4H,4W] (nearest Resize). Stands in for the 4x SR model. */
function fakeX4Onnx () {
  const scales = Buffer.alloc(16); [1, 1, 4, 4].forEach((v, i) => scales.writeFloatLE(v, i * 4))
  const tensor = Buffer.concat([intF(1, 4), intF(2, 1), strF(8, 'scales'), bytesF(9, scales)]) // dims, FLOAT, name, raw_data
  const node = Buffer.concat([strF(1, 'input'), strF(1, ''), strF(1, 'scales'), strF(2, 'output'), strF(3, 'up'), strF(4, 'Resize'),
    bytesF(5, Buffer.concat([strF(1, 'mode'), bytesF(4, Buffer.from('nearest')), intF(20, 3)]))])
  const dim = (d) => bytesF(1, typeof d === 'number' ? intF(1, d) : strF(2, d))
  const valueInfo = (name, dims) => Buffer.concat([strF(1, name), bytesF(2, bytesF(1, Buffer.concat([intF(1, 1), bytesF(2, Buffer.concat(dims.map(dim)))])))])
  const graph = Buffer.concat([bytesF(1, node), strF(2, 'fake_x4'), bytesF(5, tensor),
    bytesF(11, valueInfo('input', [1, 3, 'h', 'w'])), bytesF(12, valueInfo('output', [1, 3, 'H', 'W']))])
  return Buffer.concat([intF(1, 7), bytesF(7, graph), bytesF(8, Buffer.concat([strF(1, ''), intF(2, 13)]))])
}
/** Serves the app's asset host (ort runtime + model) the way the WebView does. */
async function routeAssets (page) {
  const dir = ortDir(); const model = fakeX4Onnx()
  await page.route('https://appassets.min.local/**', route => {
    const q = new URL(route.request().url()).pathname
    const headers = { 'access-control-allow-origin': '*' }
    if (q.startsWith('/model/')) return route.fulfill({ body: model, headers: Object.assign(headers, { 'content-type': 'application/octet-stream' }) })
    const f = q.startsWith('/ort/') ? path.join(dir, path.basename(q)) : null
    if (!f || !fs.existsSync(f)) return route.fulfill({ status: 404 })
    route.fulfill({ body: fs.readFileSync(f), headers: Object.assign(headers, { 'content-type': f.endsWith('.wasm') ? 'application/wasm' : 'text/javascript' }) })
  })
  return dir
}

/** Fulfills a media request with Range support, so the video is seekable. */
function serveMedia (route, body, type) {
  const m = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range || '')
  if (!m) return route.fulfill({ body, headers: { 'content-type': type || 'video/webm', 'accept-ranges': 'bytes' } })
  const a = +m[1]; const b = m[2] ? Math.min(+m[2], body.length - 1) : body.length - 1
  route.fulfill({ status: 206, body: body.subarray(a, b + 1), headers: { 'content-type': type || 'video/webm', 'accept-ranges': 'bytes', 'content-range': `bytes ${a}-${b}/${body.length}` } })
}

/** Waits for the neural model, then lets the performance guard forget the start-up work (loading ort + the model
    is a long task in a software-GL test browser, which makes the guard pause neural upscaling for 20 s). */
async function nnReady (page) {
  await page.waitForFunction(() => /^(ready|error)/.test(window.__minEnhance.state.stats.nn), null, { timeout: 20000 })
  await page.waitForTimeout(3500) // older than the guard's 3 s window
  await page.evaluate(() => window.__minEnhanceSet({ neural: true })) // as the app does: resets the guard
}

/** Neural frames shown on the video's renderer over one second: [id before, id after] (ids grow per result). */
async function nnShown (page, sel) {
  const id = () => page.evaluate((sel) => { const st = window.__minEnhance.state.videos.get(document.querySelector(sel)); return (st && st.nnId) || 0 }, sel || 'video')
  const a = await id(); await page.waitForTimeout(1000); return [a, await id()]
}

let failures = 0
function check (ok, msg) {
  console.log((ok ? '  ok   ' : '  FAIL ') + msg)
  if (!ok) failures++
}
/** Runs a test body with a browser; exits non-zero on a failed check or an exception. */
function run (fn, launchArgs) {
  ;(async () => {
    const browser = await chromium.launch({ args: launchArgs || GL_ARGS })
    try { await fn(browser) } finally { await browser.close() }
  })().then(() => { if (failures) { console.log(failures + ' check(s) failed'); process.exit(1) } }, (e) => { console.log('  FAIL ' + (e && e.stack || e)); process.exit(1) })
}

module.exports = { chromium, ENGINE, ENGINE_PATH, GL_ARGS, fixture, ortDir, fakeX4Onnx, routeAssets, nnReady, nnShown, serveMedia, check, run }
