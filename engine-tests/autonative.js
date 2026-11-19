// A long video (>= 10 min) that starts playing is handed to the native player once; a short one is not.
const { ENGINE, launch, run, check, canvasVideo } = require('./lib')
run('autonative: movies go to the native player once', async () => {
  const browser = await launch()
  const page = await browser.newPage()
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body><video id=s style="width:400px;height:225px"></video><video id=l style="width:400px;height:225px"></video></body>' }))
  await page.addInitScript(() => { window.__calls = []; window.AIBNative = { playing () {}, autoNative: (s, t) => { window.__calls.push(s); return true }, openNative () {} } })
  await page.goto('https://test.local/')
  await page.addScriptTag({ content: ENGINE })
  await page.evaluate(canvasVideo, { id: 's', duration: 30 }) // a trailer
  await page.evaluate(canvasVideo, { id: 'l', duration: 5400 }) // a 90 min film
  await page.waitForTimeout(600)
  const a = await page.evaluate(() => ({ calls: window.__calls.length, longPaused: document.getElementById('l').paused, shortPaused: document.getElementById('s').paused }))
  check('the film is handed over and paused in the page; the trailer keeps playing', a.calls === 1 && a.longPaused && !a.shortPaused, a)
  await page.evaluate(() => document.getElementById('l').play()) // back from the native player, play again: no second hand-over
  await page.waitForTimeout(400)
  const b = await page.evaluate(() => ({ calls: window.__calls.length, longPaused: document.getElementById('l').paused }))
  check('playing it again in the page is not handed over a second time', b.calls === 1 && !b.longPaused, b)
  await browser.close()
})
