// Aspect: a 16:9 video inside a tall 4:3 element must not be stretched (overlay = real picture rect).
const { ENGINE, check, run } = require('./lib')
run(async (browser) => {
  const page = await browser.newPage({ viewport: { width: 800, height: 700 } })
  const want = { // element 411x319, picture 16:9
    contain: { left: 0, top: 43.906, w: 411, h: 231.188, clip: '' },
    cover: { left: -78.056, top: 0, w: 567.111, h: 319, clip: 'inset(0px 78.0556px)' },
    fill: { left: 0, top: 0, w: 411, h: 319, clip: '' }
  }
  for (const fit of ['contain', 'cover', 'fill']) {
    await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: `<body style="margin:0"><video id=v muted autoplay style="width:411px;height:319px;object-fit:${fit}"></video></body>` }))
    await page.goto('https://test.local/')
    await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); setInterval(() => { x.fillStyle = '#48c'; x.fillRect(0, 0, 160, 90) }, 33); const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play() })
    await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
    await page.addScriptTag({ content: ENGINE })
    await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, target: 1080 }))
    await page.waitForTimeout(800)
    const r = await page.evaluate(() => { const c = document.querySelector('canvas[style*="fixed"]'); if (!c) return null; const s = c.style; return { left: parseFloat(s.left), top: parseFloat(s.top), w: parseFloat(s.width), h: parseFloat(s.height), clip: s.clipPath } })
    console.log('  ' + fit.padEnd(8), JSON.stringify(r))
    const e = want[fit]; const near = (a, b) => Math.abs(a - b) < 0.01
    check(!!r && near(r.left, e.left) && near(r.top, e.top) && near(r.w, e.w) && near(r.h, e.h) && r.clip === e.clip, fit + ': overlay covers exactly the picture ' + JSON.stringify(e))
    await page.unroute('https://test.local/')
  }
})
