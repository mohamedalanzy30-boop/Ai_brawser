// Measures frame-to-frame flicker of the overlay for a static scene with compression-like noise.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = path.resolve(__dirname, '../android/app/src/main/assets/engine.js')
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video></body>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => {
    const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d')
    setInterval(() => { // static edges + per-frame noise
      x.fillStyle = '#556'; x.fillRect(0, 0, 160, 90); x.fillStyle = '#ddd'; x.fillRect(40, 20, 80, 50)
      const id = x.getImageData(0, 0, 160, 90); for (let i = 0; i < id.data.length; i += 4) { const n = (Math.random() - 0.5) * 24; id.data[i] += n; id.data[i + 1] += n; id.data[i + 2] += n }
      x.putImageData(id, 0, 0) }, 33)
    const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play()
  })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: fs.readFileSync(ENGINE, 'utf8') })
  for (const temporal of [false, true]) {
    await page.evaluate(t => window.__minEnhanceSet({ enabled: true, neural: false, temporal: t, tone: false, interp: false, target: 1080 }), temporal)
    await page.waitForTimeout(1500)
    const score = await page.evaluate(async () => {
      const c = document.querySelector('canvas[style*="fixed"]'); const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d')
      const grab = () => { x.drawImage(c, 0, 0); return x.getImageData(0, 0, t.width, t.height).data.slice() }
      let total = 0, n = 0, prev = grab()
      for (let k = 0; k < 15; k++) { await new Promise(r => setTimeout(r, 70)); const cur = grab(); let s = 0; for (let i = 0; i < cur.length; i += 16) s += Math.abs(cur[i] - prev[i]); total += s / (cur.length / 16); n++; prev = cur }
      return total / n })
    console.log('temporal=' + temporal, 'mean frame-to-frame diff =', score.toFixed(2))
  }
  await browser.close()
})()
