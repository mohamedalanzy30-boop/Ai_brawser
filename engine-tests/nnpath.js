const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = fs.readFileSync(path.resolve(__dirname, '../android/app/src/main/assets/engine.js'), 'utf8'); const ortDir = '/home/user/min/node_modules/onnxruntime-web/dist'
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  page.on('console', m => { if (m.text().includes('[AIB]')) console.log('  ', m.text().slice(0, 170)) })
  await page.route('https://appassets.min.local/**', route => { const q = new URL(route.request().url()).pathname; const f = q.startsWith('/ort/') ? path.join(ortDir, q.slice(5)) : '/home/user/scratch_test/fake_x4.onnx'
    if (!fs.existsSync(f)) return route.fulfill({ status: 404 }); route.fulfill({ body: fs.readFileSync(f), headers: { 'access-control-allow-origin': '*', 'content-type': f.endsWith('.wasm') ? 'application/wasm' : f.endsWith('.onnx') ? 'application/octet-stream' : 'text/javascript' } }) })
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); setInterval(() => { x.fillStyle = '#f00'; x.fillRect(0, 0, 160, 45); x.fillStyle = '#00f'; x.fillRect(0, 45, 160, 45) }, 33); const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play() })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: fs.readFileSync(path.join(ortDir, 'ort.wasm.min.js'), 'utf8') })
  await page.addScriptTag({ content: ENGINE })
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: true, temporal: false, tone: false, target: 1080 }))
  await page.waitForTimeout(10000)
  console.log(await page.evaluate(() => { const n = window.__minEnhance.state.nn; return JSON.stringify({ stats: window.__minEnhance.state.stats, ms: n && Math.round(n.ms), hasLast: !!(n && n.last), planes: n && n.last && n.last.planes && n.last.planes.constructor.name }) }))
  await browser.close()
})()
