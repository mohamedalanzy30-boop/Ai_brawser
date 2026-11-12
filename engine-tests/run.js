// Smoke test of engine.js in headless Chromium (software GL). Usage: node run.js <ort-dist-dir> <model.onnx>
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = path.resolve(__dirname, '../android/app/src/main/assets/engine.js')
const [ortDir, modelPath] = process.argv.slice(2)
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  page.on('console', m => console.log('[page]', m.text()))
  page.on('pageerror', e => console.log('[pageerror]', e.message))
  await page.route('https://appassets.min.local/**', route => {
    const p = new URL(route.request().url()).pathname
    let file = null
    if (p.startsWith('/ort/')) file = path.join(ortDir, p.slice(5))
    else if (p.startsWith('/model/')) file = modelPath
    if (!file || !fs.existsSync(file)) return route.fulfill({ status: 404 })
    route.fulfill({ body: fs.readFileSync(file), headers: { 'access-control-allow-origin': '*', 'content-type': file.endsWith('.wasm') ? 'application/wasm' : file.endsWith('.onnx') ? 'application/octet-stream' : 'text/javascript' } })
  })
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video></body>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => { // animated 160x90 "video" from a canvas stream
    const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); let t = 0
    setInterval(() => { t++; x.fillStyle = '#334'; x.fillRect(0, 0, 160, 90); x.fillStyle = '#fc4'; x.fillRect((t * 3) % 140, 30, 20, 20); x.fillStyle = '#4cf'; x.beginPath(); x.arc(80, 60 + Math.sin(t / 5) * 15, 10, 0, 7); x.fill() }, 33)
    const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play()
  })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  if (fs.existsSync(path.join(ortDir, 'ort.wasm.min.js'))) await page.addScriptTag({ content: fs.readFileSync(path.join(ortDir, 'ort.wasm.min.js'), 'utf8') })
  await page.addScriptTag({ content: fs.readFileSync(ENGINE, 'utf8') })
  const probe = () => page.evaluate(() => {
    const c = document.querySelector('canvas[style*="fixed"]'); if (!c) return { overlay: false, stats: window.__minEnhance.state.stats }
    const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0)
    const d = x.getImageData(0, 0, t.width, t.height).data; let sum = 0, nonflat = new Set()
    for (let i = 0; i < d.length; i += 400) { sum += d[i]; nonflat.add(d[i] + ',' + d[i + 1]) }
    return { overlay: true, w: c.width, h: c.height, colors: nonflat.size, sum, stats: window.__minEnhance.state.stats, vidOpacity: document.getElementById('v').style.opacity }
  })
  for (const [name, c] of [['shader only', { neural: false }], ['+interp', { neural: false, interp: true }], ['neural', { neural: true, interp: false }]]) {
    await page.evaluate(c => window.__minEnhanceSet(Object.assign({ enabled: true, target: 1080 }, c)), c)
    await page.waitForTimeout(name === 'neural' ? 12000 : 2500)
    console.log(name, JSON.stringify(await probe()))
  }
  await page.screenshot({ path: path.join(__dirname, 'shot.png') })
  await browser.close()
})()
