// Ghosting: a bright block jumps away on a dark background; measure what is left at the old spot.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = path.resolve(__dirname, '../android/app/src/main/assets/engine.js')
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video></body>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => {
    const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); window.__pos = 20
    setInterval(() => { x.fillStyle = '#111'; x.fillRect(0, 0, 160, 90); x.fillStyle = '#fff'; x.fillRect(window.__pos, 30, 30, 30) }, 33)
    const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play()
  })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: fs.readFileSync(ENGINE, 'utf8') })
  for (const temporal of [false, true]) {
    await page.evaluate(t => { window.__pos = 20; window.__minEnhanceSet({ enabled: true, neural: false, temporal: t, tone: false, interp: false, target: 1080 }) }, temporal)
    await page.waitForTimeout(1500)
    await page.evaluate(() => { window.__pos = 110 })
    await page.waitForTimeout(150)
    const left = await page.evaluate(() => {
      const c = document.querySelector('canvas[style*="fixed"]'); const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0)
      const sx = c.width / 160, sy = c.height / 90; const d = x.getImageData(Math.round(30 * sx), Math.round(40 * sy), 10, 10).data
      let s = 0; for (let i = 0; i < d.length; i += 4) s += d[i]; return s / (d.length / 4) })
    console.log('temporal=' + temporal, 'brightness left at old position (0=clean, 255=full ghost) =', left.toFixed(1))
  }
  await browser.close()
})()
