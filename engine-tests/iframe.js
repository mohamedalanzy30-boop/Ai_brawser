// Player inside an iframe: a setting changed AFTER load (from the top page, like the app does) must reach the iframe.
const { ENGINE, launch, run, check } = require('./lib')
run('iframe: settings reach embedded players', async () => {
  const browser = await launch()
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  const inner = '<body style="margin:0"><video id=v muted style="width:640px;height:360px"></video><script>const c=document.createElement("canvas");c.width=160;c.height=90;const x=c.getContext("2d");setInterval(()=>{x.fillStyle="#345";x.fillRect(0,0,160,90)},33);v.srcObject=c.captureStream(30);v.play()</script>'
  await page.route('https://player.local/', r => r.fulfill({ contentType: 'text/html', body: inner }))
  await page.route('https://site.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><iframe src="https://player.local/" style="width:660px;height:380px;border:0"></iframe>' }))
  await page.addInitScript('window.__minCfg0 = {enabled:false, neural:false}; ' + ENGINE) // document-start injection into every frame, switched off
  await page.goto('https://site.local/'); await page.waitForTimeout(2000)
  const frame = page.frames().find(f => f.url().includes('player.local'))
  const overlay = () => frame.evaluate(() => { const c = document.querySelector('canvas[style*="fixed"]'); return !!c && c.style.display === 'block' })
  check('switched off: no overlay in the iframe', await overlay() === false)
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, target: 1080 })) // top frame only
  await page.waitForTimeout(2000)
  check('switched on from the top page: the iframe player is enhanced', await overlay() === true)
  await page.evaluate(() => window.__minEnhanceSet({ enabled: false }))
  await page.waitForTimeout(800)
  const off = await frame.evaluate(() => ({ overlays: document.querySelectorAll('canvas[style*="fixed"]').length, opacity: document.getElementById('v').style.opacity }))
  check('switched off again: overlay gone, page video visible', off.overlays === 0 && off.opacity !== '0', off)
  await browser.close()
})
