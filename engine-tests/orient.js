// Orientation test: top half red, bottom half blue. Reads overlay pixels for each render path.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = path.resolve(__dirname, '../android/app/src/main/assets/engine.js')
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  const ortDir = '/home/user/min/node_modules/onnxruntime-web/dist'
  await page.route('https://appassets.min.local/**', route => {
    const q = new URL(route.request().url()).pathname; let f = null
    if (q.startsWith('/ort/')) f = path.join(ortDir, q.slice(5)); else if (q.includes('rife')) f = '/home/user/scratch_test/fake_rife.onnx'; else if (q.startsWith('/model/')) f = '/home/user/scratch_test/fake_x4.onnx'
    if (!f || !fs.existsSync(f)) return route.fulfill({ status: 404 })
    route.fulfill({ body: fs.readFileSync(f), headers: { 'access-control-allow-origin': '*', 'content-type': f.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' } })
  })
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video></body>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => {
    const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); let t = 0
    setInterval(() => { t++; x.fillStyle = '#f00'; x.fillRect(0, 0, 160, 45); x.fillStyle = '#00f'; x.fillRect(0, 45, 160, 45); x.fillStyle = '#0f0'; x.fillRect(t % 150, 0, 4, 4) }, 33)
    const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play()
  })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: fs.readFileSync(path.join(ortDir, 'ort.wasm.min.js'), 'utf8') })
  await page.addScriptTag({ content: fs.readFileSync(ENGINE, 'utf8') })
  const probe = () => page.evaluate(() => {
    const c = document.querySelector('canvas[style*="fixed"]'); if (!c) return 'no overlay'
    const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0)
    const top = x.getImageData(c.width / 2, 20, 1, 1).data, bot = x.getImageData(c.width / 2, c.height - 20, 1, 1).data
    return 'top=' + Array.from(top).slice(0, 3) + ' bottom=' + Array.from(bot).slice(0, 3)
  })
  for (const [name, c] of [['cur no-temporal', { neural: false, temporal: false, tone: false }], ['cur + temporal', { neural: false, temporal: true, tone: false }], ['neural SR (nn)', { neural: true, temporal: false, tone: false, interp: false }], ['neural RIFE (raw)', { neural: false, temporal: false, tone: false, interp: true, interpNeural: true }], ['interp shader (mid)', { neural: false, temporal: false, interp: true, interpNeural: false, tone: false }]]) {
    await page.evaluate(c => window.__minEnhanceSet(Object.assign({ enabled: true, target: 1080 }, c)), c)
    await page.waitForTimeout(name.startsWith('neural') ? 9000 : 2000); console.log(name.padEnd(22), await probe())
  }
  await browser.close()
})()
