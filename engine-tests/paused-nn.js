// Regression: a paused video runs the neural model once per frame, not again and again on the same frame.
const { ENGINE, fixture, serveMedia, check, run } = require('./lib')
run(async (browser) => {
  const page = await browser.newPage({ viewport: { width: 800, height: 700 } })
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><video id=v muted src="/v.webm" style="width:800px;height:450px"></video></body>' }))
  await page.route('https://test.local/v.webm', r => serveMedia(r, fixture('v360.webm')))
  await page.route('https://appassets.min.local/**', r => r.fulfill({ body: Buffer.from([1, 2, 3]), headers: { 'access-control-allow-origin': '*' } }))
  await page.goto('https://test.local/')
  await page.evaluate(() => { // mock ONNX Runtime: counts model runs, returns a 4x output of the right size
    window.__runs = 0
    window.ort = {
      env: { wasm: {} },
      Tensor: function (t, d, dims) { this.dims = dims },
      InferenceSession: { create: async () => ({ inputNames: ['i'], outputNames: ['o'], run: async (f) => { window.__runs++; const [, , h, w] = f.i.dims; await new Promise(r => setTimeout(r, 30)); return { o: { data: new Float32Array(3 * 16 * w * h) } } } }) }
    }
  })
  await page.waitForFunction(() => document.getElementById('v').readyState >= 2)
  await page.evaluate(() => { window.__minCfg0 = Object.assign(window.__minCfg0 || {}, { nnCpu: true }) }) // no WebGPU in headless Chromium: test the model on wasm
  await page.addScriptTag({ content: ENGINE })
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: true, target: 1080 }))
  const runs = () => page.evaluate(() => window.__runs)
  await page.waitForTimeout(1500) // paused, never played
  const a = await runs(); await page.waitForTimeout(4000); const b = await runs()
  console.log('  paused (never played): runs before =', a, ' runs in next 4 s =', b - a)
  check(a === 1, 'paused on the first frame: the model ran exactly once (' + a + ')')
  check(b - a === 0, 'paused on the same frame: no further model runs (' + (b - a) + ')')
  check(await page.evaluate(() => { const n = window.__minEnhance.state.nn; const st = window.__minEnhance.state.videos.get(document.getElementById('v')); return !!n.last && st.nnId === n.last.id && n.last.planes === null }), 'the result is shown and its Float32 planes were released after the GPU upload')

  await page.evaluate(() => document.getElementById('v').play()); await page.waitForTimeout(1500)
  await page.evaluate(() => { const v = document.getElementById('v'); v.pause(); v.currentTime = 3 }); await page.waitForTimeout(1000)
  const c = await runs(); await page.waitForTimeout(4000); const d = await runs()
  console.log('  paused after play + seek: runs in 4 s =', d - c)
  check(d - c === 0, 'paused after a seek: no repeated model runs (' + (d - c) + ')')
  const shown = await page.evaluate(() => { const n = window.__minEnhance.state.nn; return !!(n && n.last && Math.abs(n.last.mediaTime - 3) < 0.05) })
  check(shown, 'the seeked frame was upscaled once (last result is at t=3)')

  await page.evaluate(() => { document.getElementById('v').currentTime = 5 }); await page.waitForTimeout(1200)
  const e = await runs(); await page.waitForTimeout(2000); const f = await runs()
  check(e - d === 1, 'a new seek while paused runs the model once more (' + (e - d) + ')')
  check(f - e === 0, 'and then stays idle (' + (f - e) + ')')
})
