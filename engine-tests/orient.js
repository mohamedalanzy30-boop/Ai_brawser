// Orientation and colours on both render paths (shader on the current frame, neural output): top half red, bottom half blue.
// A flip anywhere (upload, render-to-texture, model planes) shows up as blue on top.
const { ENGINE, launch, run, check, canvasVideo, routeAssets, ortScript } = require('./lib')
run('orient: picture the right way up on every path', async () => {
  const browser = await launch()
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  await routeAssets(page)
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v style="width:640px;height:360px"></video></body>' }))
  await page.goto('https://test.local/')
  await page.evaluate(canvasVideo, { id: 'v', split: true })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: ortScript() })
  await page.addScriptTag({ content: ENGINE })
  const probe = () => page.evaluate(() => {
    const c = document.querySelector('canvas[style*="fixed"]'); if (!c || c.style.display !== 'block') return null
    const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0)
    const st = window.__minEnhance.state.videos.get(document.getElementById('v'))
    return { top: Array.from(x.getImageData(c.width / 2, 20, 1, 1).data).slice(0, 3), bottom: Array.from(x.getImageData(c.width / 2, c.height - 20, 1, 1).data).slice(0, 3), nnShown: !!(st && st.nnId), glError: st && st.R ? st.R.gl.getError() : -1 }
  })
  const redTop = p => p && p.top[0] > 180 && p.top[2] < 80 && p.bottom[2] > 180 && p.bottom[0] < 80
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, tone: false, target: 1080 }))
  await page.waitForTimeout(1500)
  const a = await probe()
  check('shader path: red on top, blue at the bottom', redTop(a) && a.glError === 0, a)
  await page.evaluate(() => window.__minEnhanceSet({ neural: true }))
  await page.waitForFunction(() => { const st = window.__minEnhance.state.videos.get(document.getElementById('v')); return st && st.nnId }, null, { timeout: 30000 }).catch(() => {})
  await page.evaluate(() => document.getElementById('v').pause()) // the paused frame is always shown from the model
  await page.waitForTimeout(2500)
  const b = await probe()
  check('neural path: model output shown, red on top, blue at the bottom', b && b.nnShown && redTop(b) && b.glError === 0, b)
  await browser.close()
})
