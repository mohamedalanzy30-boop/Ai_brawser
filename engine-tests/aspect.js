// Aspect: a 16:9 video inside a 4:3 element must not be stretched (overlay = the real picture rect, per object-fit).
const { ENGINE, launch, run, check, canvasVideo } = require('./lib')
run('aspect: overlay follows object-fit', async () => {
  const browser = await launch()
  const page = await browser.newPage({ viewport: { width: 800, height: 700 } })
  const want = { contain: [411, 231.2, false], cover: [567.1, 319, true], fill: [411, 319, false] } // w, h, clipped
  for (const fit of Object.keys(want)) {
    await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: `<body style="margin:0"><video id=v style="width:411px;height:319px;object-fit:${fit}"></video></body>` }))
    await page.goto('https://test.local/')
    await page.evaluate(canvasVideo, 'v')
    await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
    await page.addScriptTag({ content: ENGINE })
    await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, target: 1080 }))
    await page.waitForTimeout(800)
    const r = await page.evaluate(() => { const c = document.querySelector('canvas[style*="fixed"]'); if (!c) return null; const s = c.style; return { w: parseFloat(s.width), h: parseFloat(s.height), clip: s.clipPath } })
    const [w, h, clipped] = want[fit]
    check(fit + ': overlay ' + w + 'x' + h + (clipped ? ' clipped to the element' : ''), r && Math.abs(r.w - w) < 1 && Math.abs(r.h - h) < 1 && (!!r.clip && r.clip !== 'none') === clipped, r)
    await page.unroute('https://test.local/')
  }
  await browser.close()
})
