// Page neural path with the real ONNX Runtime Web (wasm) and a stand-in 4x model: results reach the GPU,
// and the large Float32 output is not kept once uploaded.
const fs = require('fs'); const path = require('path')
const { ENGINE, routeAssets, nnReady, nnShown, check, run } = require('./lib')
run(async (browser) => {
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  const errors = []; page.on('pageerror', e => errors.push(e.message))
  const ortDir = await routeAssets(page)
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); setInterval(() => { x.fillStyle = '#f00'; x.fillRect(0, 0, 160, 45); x.fillStyle = '#00f'; x.fillRect(0, 45, 160, 45) }, 33); const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play() })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: fs.readFileSync(path.join(ortDir, 'ort.wasm.min.js'), 'utf8') })
  await page.evaluate(() => { window.__minCfg0 = Object.assign(window.__minCfg0 || {}, { nnCpu: true }) }) // no WebGPU in headless Chromium: test the model on wasm
  await page.addScriptTag({ content: ENGINE })
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: true, tone: false, target: 1080 }))
  await nnReady(page)
  await page.waitForTimeout(1500)
  const state = () => page.evaluate(() => {
    const n = window.__minEnhance.state.nn; const st = window.__minEnhance.state.videos.get(document.getElementById('v'))
    return { stats: window.__minEnhance.state.stats, ms: n && Math.round(n.ms), id: n && n.last && n.last.id, nnId: st && st.nnId, w: n && n.last && n.last.w, h: n && n.last && n.last.h, planes: n && n.last && (n.last.planes ? n.last.planes.constructor.name : null) }
  })
  const r = await state(); console.log('  playing:', JSON.stringify(r))
  check(r.stats.nn === 'ready:wasm', 'model loaded on the wasm backend (' + r.stats.nn + ')')
  const [a, b] = await nnShown(page)
  check(b - a >= 5, 'playing: neural frames keep reaching the video renderer (' + (b - a) + ' in 1 s)')
  await page.evaluate(() => document.getElementById('v').pause()); await page.waitForTimeout(1500)
  const p = await state(); console.log('  paused: ', JSON.stringify(p))
  check(p.w === 640 && p.h === 360, 'neural result is 4x the 160x90 source')
  check(p.nnId === p.id && p.planes === null, 'paused: the shown neural frame is on the GPU and its Float32 planes are released')
  check(errors.length === 0, 'no page errors ' + JSON.stringify(errors))
  // detaching the video frees its neural state
  await page.evaluate(() => window.__minEnhanceSet({ enabled: false }))
  check(await page.evaluate(() => window.__minEnhance.state.nn.last === null && window.__minEnhance.state.videos.size === 0), 'disabling frees the video and its neural result')
})
