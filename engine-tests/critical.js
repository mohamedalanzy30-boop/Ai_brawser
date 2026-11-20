// The two faults that hurt most on the phone:
//  1) "Enhance images" on a page full of images from another site must never take the video's GPU context away
//     (the video was left hidden under a dead overlay: a white box where the film should be).
//  2) A paused video must not keep running the neural model on the same frame (battery and heat).
const { ENGINE, launch, run, check, canvasVideo, fileVideo } = require('./lib')

run('critical: images vs video context, paused video vs neural model', async () => {
  const browser = await launch()
  const page = await browser.newPage({ viewport: { width: 800, height: 900 } })
  page.on('pageerror', e => console.log('   [pageerror]', e.message))
  // a 100x60 PNG, served from another site WITHOUT CORS headers (the usual case: image CDNs)
  const png = Buffer.from(await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 100; c.height = 60; const x = c.getContext('2d'); x.fillStyle = '#c84'; x.fillRect(0, 0, 100, 60); return c.toDataURL('image/png').split(',')[1] }), 'base64')
  await page.route('https://img.other.local/**', r => r.fulfill({ contentType: 'image/png', body: png }))
  await page.route('https://test.local/same/**', r => r.fulfill({ contentType: 'image/png', body: png }))
  const imgs = Array.from({ length: 30 }, (_, i) => `<img src="https://img.other.local/${i}.png" style="width:300px;height:180px">`).join('') +
    Array.from({ length: 6 }, (_, i) => `<img src="/same/${i}.png" style="width:300px;height:180px">`).join('')
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: `<body style="margin:0"><video id=v style="width:640px;height:360px;display:block"></video>${imgs}</body>` }))
  await page.goto('https://test.local/')
  await page.evaluate(canvasVideo, 'v')
  await page.waitForFunction(() => document.getElementById('v').videoWidth > 0)
  await page.addScriptTag({ content: ENGINE })
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, images: true, target: 1080 }))
  await page.waitForTimeout(7000) // all 36 images go through the image queue (one every 120 ms)
  const r1 = await page.evaluate(() => {
    const v = document.getElementById('v'); const st = window.__minEnhance.state.videos.get(v)
    const c = st && st.canvas; let px = null
    if (c && c.style.display === 'block') { const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0); px = Array.from(x.getImageData(c.width / 2, c.height / 2, 1, 1).data).slice(0, 3) }
    return { attached: !!st, lost: !!(st && (!st.R || st.R.gl.isContextLost())), overlay: c && c.style.display, videoOpacity: v.style.opacity, px, enhancedSame: document.querySelectorAll('img[data-min-enhanced]').length }
  })
  check('video GPU context still alive after 36 images', r1.attached && !r1.lost, r1)
  check('video picture shown (overlay draws the video colour)', r1.overlay === 'block' && r1.px && r1.px[2] > 150 && r1.px[0] < 120, r1.px)
  check('same-site images still enhanced', r1.enhancedSame === 6, r1.enhancedSame)
  // a lost context (driver reset, too many contexts) must give the page its own video back at once
  const r2 = await page.evaluate(async () => {
    const v = document.getElementById('v'); const st = window.__minEnhance.state.videos.get(v)
    st.R.gl.getExtension('WEBGL_lose_context').loseContext()
    await new Promise(r => setTimeout(r, 300))
    return { videoOpacity: v.style.opacity, overlayInPage: document.contains(st.canvas) }
  })
  check('context lost -> page video visible again, dead overlay removed', r2.videoOpacity !== '0' && !r2.overlayInPage, r2)
  await page.waitForTimeout(1500)
  const r3 = await page.evaluate(() => { const v = document.getElementById('v'); const st = window.__minEnhance.state.videos.get(v); return { attached: !!st, lost: !!(st && (!st.R || st.R.gl.isContextLost())), overlay: st && st.canvas && st.canvas.style.display } })
  check('enhancement comes back on a fresh context', r3.attached && !r3.lost && r3.overlay === 'block', r3)

  await page.close() // one page at a time: a page left open in front keeps the next one's video frames from ticking

  // ---- 2) paused video: the model runs once for the paused frame, then stays idle
  const stubPage = async (delayMs, play, file) => { // ONNX Runtime stand-in: counts model runs, returns a 4x picture after delayMs
    const p = await browser.newPage({ viewport: { width: 800, height: 500 } })
    await p.route('https://appassets.min.local/**', r => r.fulfill({ body: Buffer.from('fake'), headers: { 'access-control-allow-origin': '*' } }))
    await p.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0;height:3000px"><video id=v style="width:640px;height:360px;display:block"></video></body>' }))
    await p.addInitScript((delay) => {
      window.__runs = 0
      window.ort = {
        env: { wasm: {} },
        Tensor: function (type, data, dims) { this.type = type; this.data = data; this.dims = dims },
        InferenceSession: { create: async () => ({ inputNames: ['x'], outputNames: ['y'], run: async (f) => { window.__runs++; const d = f.x.dims; if (delay) await new Promise(r => setTimeout(r, delay)); return { y: { data: new Float32Array(3 * d[2] * 4 * d[3] * 4) } } } }) }
      }
    }, delayMs)
    await p.goto('https://test.local/')
    await p.evaluate(file ? fileVideo : canvasVideo, { id: 'v', play })
    await p.waitForFunction(() => document.getElementById('v').videoWidth > 0)
    await p.addScriptTag({ content: ENGINE })
    await p.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: true, tone: false, images: false, target: 1080 }))
    return p
  }
  const runs = (p) => p.evaluate(() => window.__runs)
  // the usual case in this browser (no autoplay): the video sits paused on the page. The old engine ran the model ~4 times a second on it.
  const p2 = await stubPage(0, false, true) // a video file, like on a site
  await p2.waitForTimeout(1500)
  const a = await runs(p2)
  await p2.waitForTimeout(5000)
  const b = await runs(p2)
  check('paused video: one model run for its frame, none in the next 5 s', a >= 1 && b - a === 0, { first: a, next5s: b - a })
  await p2.evaluate(() => document.getElementById('v').play())
  await p2.waitForTimeout(1500)
  const c = await runs(p2)
  check('playing: the model runs on the frames', c - b > 3, { runsAfterPlay: c - b })
  // paused after playing, then scrolled: the overlay must follow the video (the loop used to wait for a frame that never came)
  await p2.evaluate(() => document.getElementById('v').pause())
  await p2.waitForTimeout(800)
  const d = await runs(p2)
  await p2.evaluate(() => window.scrollTo(0, 200))
  await p2.waitForTimeout(1200)
  const pos = await p2.evaluate(() => { const v = document.getElementById('v'); const st = window.__minEnhance.state.videos.get(v); return { overlayTop: st && st.canvas && parseFloat(st.canvas.style.top), videoTop: v.getBoundingClientRect().top } })
  check('paused, then scrolled: the overlay follows the video', pos.overlayTop === pos.videoTop, pos)
  await p2.waitForTimeout(3000)
  const e = await runs(p2)
  check('paused after playing: at most one more model run', e - d <= 1, { runsWhilePaused: e - d })
  await p2.close()

  // ---- 3) a model too slow for live video is not given up for good: tried again every 5 s
  const p3 = await stubPage(60, true)
  await p3.waitForTimeout(12500)
  const n = await p3.evaluate(() => window.__runs)
  check('slow model: re-checked every 5 s while playing (not every frame, not never)', n >= 2 && n <= 5, { runsIn12s: n })
  await browser.close()
})
