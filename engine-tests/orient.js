// Orientation test: top half red, bottom half blue. Reads overlay pixels for each render path.
const fs = require('fs'); const path = require('path')
const { ENGINE, GL_ARGS, routeAssets, nnReady, nnShown, check, run } = require('./lib')
run(async (browser) => {
  const page = await browser.newPage({ viewport: { width: 800, height: 500 } })
  const ortDir = await routeAssets(page)
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted autoplay style="width:640px;height:360px"></video></body>' }))
  await page.goto('https://test.local/')
  await page.evaluate(() => {
    const c = document.createElement('canvas'); c.width = 160; c.height = 90; const x = c.getContext('2d'); let t = 0
    setInterval(() => { t++; x.fillStyle = '#f00'; x.fillRect(0, 0, 160, 45); x.fillStyle = '#00f'; x.fillRect(0, 45, 160, 45); x.fillStyle = '#0f0'; x.fillRect(t % 150, 0, 4, 4) }, 33)
    const v = document.getElementById('v'); v.srcObject = c.captureStream(30); v.play()
  })
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: fs.readFileSync(path.join(ortDir, 'ort.wasm.min.js'), 'utf8') })
  await page.evaluate(() => { window.__minCfg0 = Object.assign(window.__minCfg0 || {}, { nnCpu: true }) }) // no WebGPU in headless Chromium: test the model on wasm
  await page.addScriptTag({ content: ENGINE })
  const probe = () => page.evaluate(() => {
    const c = document.querySelector('canvas[style*="fixed"]'); if (!c) return null
    const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0)
    return { top: Array.from(x.getImageData(c.width / 2, 20, 1, 1).data).slice(0, 3), bottom: Array.from(x.getImageData(c.width / 2, c.height - 20, 1, 1).data).slice(0, 3) }
  })
  const red = (p) => p[0] > 180 && p[1] < 80 && p[2] < 80; const blue = (p) => p[2] > 180 && p[0] < 80 && p[1] < 80
  for (const [name, c] of [['current frame', { neural: false, tone: false }], ['neural SR', { neural: true, tone: false }]]) {
    await page.evaluate(c => window.__minEnhanceSet(Object.assign({ enabled: true, target: 1080 }, c)), c)
    if (c.neural) await nnReady(page)
    await page.waitForTimeout(1500)
    const p = await probe(); console.log('  ' + name.padEnd(14), JSON.stringify(p))
    check(!!p && red(p.top) && blue(p.bottom), name + ': top red, bottom blue (not flipped)')
    if (c.neural) {
      const [a, b] = await nnShown(page)
      check(b - a >= 5, 'the neural path is used (' + (b - a) + ' neural frames in 1 s)')
      const glError = await page.evaluate(() => window.__minEnhance.state.videos.get(document.getElementById('v')).R.gl.getError())
      check(glError === 0, 'no WebGL error (' + glError + ')')
    }
  }
}, GL_ARGS.concat('--ignore-gpu-blocklist'))
