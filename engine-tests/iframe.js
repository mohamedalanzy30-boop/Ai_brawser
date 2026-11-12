// Player inside an iframe: config changed AFTER load must still reach the iframe.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path')
const ENGINE = fs.readFileSync(path.resolve(__dirname, '../android/app/src/main/assets/engine.js'), 'utf8')
;(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  page.on('console', m => { if (m.text().includes('[AIB]')) console.log('  ', m.text().slice(0, 150)) })
  const inner = '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video><script>const c=document.createElement("canvas");c.width=160;c.height=90;const x=c.getContext("2d");setInterval(()=>{x.fillStyle="#345";x.fillRect(0,0,160,90)},33);v.srcObject=c.captureStream(30);v.play()</script>'
  await page.route('https://player.local/', r => r.fulfill({ contentType: 'text/html', body: inner }))
  await page.route('https://site.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><iframe src="https://player.local/" style="width:660px;height:380px;border:0"></iframe>' }))
  // simulate document-start injection with enabled=false
  await page.addInitScript('window.__minCfg0 = {enabled:false, neural:false}; ' + ENGINE)
  await page.goto('https://site.local/'); await page.waitForTimeout(2000)
  const frame = page.frames().find(f => f.url().includes('player.local'))
  const overlay = () => frame.evaluate(() => !!document.querySelector('canvas[style*="fixed"]'))
  console.log('before enable: overlay in iframe =', await overlay())
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, target: 1080 })) // top frame only, like the app
  await page.waitForTimeout(2000)
  console.log('after enable from top frame: overlay in iframe =', await overlay())
  await browser.close()
})()
