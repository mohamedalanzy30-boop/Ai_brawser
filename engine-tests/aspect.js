// Aspect: a 16:9 video inside a tall 4:3 element must not be stretched (overlay = real picture rect).
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = path.resolve(__dirname, '../android/app/src/main/assets/engine.js')
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 700 } })
  for (const fit of ['contain', 'cover', 'fill']) {
    await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: `<body style="margin:0"><video id=v muted autoplay style="width:411px;height:319px;object-fit:${fit}"></video></body>` }))
    await page.goto('https://test.local/')
    await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); setInterval(() => { x.fillStyle = '#48c'; x.fillRect(0, 0, 160, 90) }, 33); const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play() })
    await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
    await page.addScriptTag({ content: fs.readFileSync(ENGINE, 'utf8') })
    await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, target: 1080 }))
    await page.waitForTimeout(800)
    const r = await page.evaluate(() => { const c = document.querySelector('canvas[style*="fixed"]'); const s = c.style; return { left: s.left, top: s.top, w: s.width, h: s.height, clip: s.clipPath, ratio: (parseFloat(s.width) / parseFloat(s.height)).toFixed(3) } })
    console.log(fit.padEnd(8), JSON.stringify(r))
    await page.unroute('https://test.local/')
  }
  await browser.close()
})()
