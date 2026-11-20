// Regression: image upscaling must not create a WebGL context per image. Cross-origin images used to leak one
// context each until Chrome dropped the oldest one, the video's, which left a white area where the video was.
const { ENGINE, fixture, serveMedia, check, run } = require('./lib')
run(async (browser) => {
  const page = await browser.newPage({ viewport: { width: 800, height: 900 } })
  const own = Array.from({ length: 4 }, (_, i) => `<img src="/s${i}.png" style="width:300px;height:180px">`).join('')
  const foreign = Array.from({ length: 30 }, (_, i) => `<img src="https://cdn.other/i${i}.png" style="width:300px;height:180px">`).join('')
  await page.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: `<body style="margin:0"><video id=v muted loop src="/v.webm" style="width:800px;height:450px"></video><div><img id=lazy src="/placeholder.png" style="width:300px;height:180px">${own}${foreign}</div></body>` }))
  await page.route('https://test.local/v.webm', r => serveMedia(r, fixture('v360.webm')))
  await page.route('https://test.local/real.png', r => r.fulfill({ contentType: 'image/png', body: fixture('big.png') }))
  await page.route(/https:\/\/(test\.local\/(s\d|placeholder)|cdn\.other\/).*\.png/, r => r.fulfill({ contentType: 'image/png', body: fixture('img.png') }))
  await page.addInitScript(() => {
    // count WebGL contexts the page creates
    window.__ctx = new Set(); const get = HTMLCanvasElement.prototype.getContext
    HTMLCanvasElement.prototype.getContext = function (t, o) { const c = get.call(this, t, o); if (c && /webgl/.test(t)) window.__ctx.add(this); return c }
    // a lazy loader swaps #lazy to its real picture while the first upscaled copy is being encoded
    const toBlob = HTMLCanvasElement.prototype.toBlob
    HTMLCanvasElement.prototype.toBlob = function (cb, ...a) {
      return toBlob.call(this, (b) => { const l = document.getElementById('lazy'); if (l && !l.dataset.swapped) { l.dataset.swapped = '1'; l.src = '/real.png' } cb(b) }, ...a)
    }
  })
  await page.goto('https://test.local/')
  await page.evaluate(() => document.getElementById('v').play())
  await page.waitForFunction(() => document.getElementById('v').readyState >= 2)
  await page.addScriptTag({ content: ENGINE })
  await page.evaluate(() => window.__minEnhanceSet({ enabled: true, neural: false, images: true, target: 1080 }))
  await page.waitForTimeout(8000)
  const r = await page.evaluate(() => {
    const v = document.getElementById('v'); const st = window.__minEnhance.state.videos.get(v); const l = document.getElementById('lazy')
    return {
      contexts: window.__ctx.size,
      enhanced: !!st,
      lost: st ? st.R.gl.isContextLost() : null,
      opacity: v.style.opacity,
      overlay: st ? st.canvas.style.display : null,
      playing: !v.paused,
      ownUpscaled: Array.from(document.querySelectorAll('img[src^="blob:"]')).filter(i => i.id !== 'lazy').length,
      lazy: { src: l.src.replace(location.origin, ''), w: l.naturalWidth }
    }
  })
  console.log('  ' + JSON.stringify(r))
  check(r.contexts <= 2, 'at most 2 WebGL contexts (video + shared image renderer), got ' + r.contexts)
  check(r.enhanced && r.lost === false, 'the video renderer context is still alive')
  check(r.playing && (r.overlay === 'block' ? r.lost === false && r.opacity === '0' : r.opacity !== '0'), 'the video stays visible: a live overlay or the real video (overlay ' + r.overlay + ', lost ' + r.lost + ', video opacity "' + r.opacity + '")')
  check(r.overlay === 'block', 'the video is still enhanced')
  check(r.ownUpscaled === 4, 'all 4 same-origin images were upscaled (' + r.ownUpscaled + ')')
  check(r.lazy.src === '/real.png' && r.lazy.w === 400, 'an image whose src changed during processing keeps its new src (' + r.lazy.src + ')')
  // if the browser still drops the video's context, the real video must come back instead of a dead canvas
  await page.evaluate(() => { const st = window.__minEnhance.state.videos.get(document.getElementById('v')); const e = st && st.R.gl.getExtension('WEBGL_lose_context'); if (e) e.loseContext() })
  await page.waitForTimeout(1500)
  const after = await page.evaluate(() => { const v = document.getElementById('v'); return { attached: window.__minEnhance.state.videos.has(v), opacity: v.style.opacity, overlays: document.querySelectorAll('canvas[style*="fixed"]').length } })
  console.log('  after context loss: ' + JSON.stringify(after))
  check(!after.attached && after.opacity === '' && after.overlays === 0, 'context loss: overlay removed, original video visible again and not re-attached')
})
