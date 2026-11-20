// Faults found in review, each one a case seen on real sites:
//  1) a video from another site without CORS (cannot be read) switched the neural model off for the whole page, and got a
//     new GPU context on every page change
//  2) a paused overlay kept an old picture: a new source at the same time (a), another paused video's model result (b)
//  3) the <video> element itself in fullscreen (native controls) was a black screen; a video outside the fullscreen box
//     had its overlay drawn over the film
//  4) a site that keeps its video at opacity 0 until it plays: the film was left invisible when the overlay went away
//  5) see-through images (logos) came out with a black background
//  6) an image given another picture while its enhanced copy was encoded got the copy of the old picture
const { ENGINE, launch, run, check, fileVideo, webmBase64, ortStub } = require('./lib')

const RED = '#f00'; const LIME = '#0f0'
const isRed = px => !!px && px[0] > 150 && px[1] < 100 && px[2] < 100
const isLime = px => !!px && px[1] > 150 && px[0] < 100 && px[2] < 100
/** in-page: overlay of a video (display, centre pixel of what it draws, page video opacity) */
function overlay (id) {
  const v = document.getElementById(id); const st = window.__minEnhance.state.videos.get(v); const c = st && st.canvas
  let px = null
  if (c && c.style.display === 'block') { const t = document.createElement('canvas'); t.width = c.width; t.height = c.height; const x = t.getContext('2d'); x.drawImage(c, 0, 0); px = Array.from(x.getImageData(c.width / 2 | 0, c.height / 2 | 0, 1, 1).data).slice(0, 3) }
  return { attached: !!st, display: c ? c.style.display : null, px, opacity: v.style.opacity }
}

run('review: unreadable videos, stale paused pictures, fullscreen, opacity, images', async () => {
  const browser = await launch()
  const newPage = async (html, init) => {
    const p = await browser.newPage({ viewport: { width: 800, height: 900 } })
    p.on('pageerror', e => console.log('   [pageerror]', e.message))
    if (init) await p.addInitScript(init.fn, init.arg)
    await p.route('https://appassets.min.local/**', r => r.fulfill({ body: Buffer.from('fake'), headers: { 'access-control-allow-origin': '*' } }))
    await p.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: html }))
    return p
  }
  const engine = async (p, c) => { await p.addScriptTag({ content: ENGINE }); await p.evaluate((c) => window.__minEnhanceSet(Object.assign({ enabled: true, images: false, tone: false, target: 1080, sharpness: 0, denoise: 0 }, c)), c) }

  // ---- 1) a cross-site video without CORS next to a readable one
  {
    const clip = Buffer.from(await (async () => { const q = await browser.newPage(); await q.goto('about:blank'); const b = await q.evaluate(webmBase64, { color: LIME }); await q.close(); return b })(), 'base64')
    const p = await newPage('<body style="margin:0"><video id=x src="https://other.local/clip.webm" muted loop style="width:400px;height:225px;display:block"></video><video id=v style="width:400px;height:225px;display:block"></video></body>', { fn: ortStub, arg: 0 })
    await p.addInitScript(() => { // count the GPU contexts the page asks for
      window.__gl = 0; const g = HTMLCanvasElement.prototype.getContext
      HTMLCanvasElement.prototype.getContext = function (t, o) { if (t === 'webgl2') window.__gl++; return g.call(this, t, o) }
    })
    await p.route('https://other.local/clip.webm', r => r.fulfill({ contentType: 'video/webm', body: clip })) // no CORS header
    await p.goto('https://test.local/')
    await p.evaluate(fileVideo, { id: 'v', color: RED })
    await p.waitForFunction(() => document.getElementById('x').readyState >= 2)
    await engine(p, { neural: true })
    await p.evaluate(() => { document.getElementById('x').play(); document.getElementById('v').play() })
    await p.waitForTimeout(3000)
    const a = await p.evaluate(() => { const s = window.__minEnhance.state; const st = s.videos.get(document.getElementById('v')); return { nn: s.stats.nn, runs: window.__runs, nnShown: !!(st && st.nnId), otherAttached: s.videos.has(document.getElementById('x')) } })
    check('cross-site video: the readable video keeps the neural model', /^ready:/.test(a.nn) && a.runs > 0 && a.nnShown, a)
    const gl0 = await p.evaluate(() => window.__gl)
    for (let i = 0; i < 3; i++) { await p.evaluate(() => document.body.appendChild(document.createElement('div'))); await p.waitForTimeout(700) } // page changes: a scan each
    const b = await p.evaluate(() => ({ gl: window.__gl, otherAttached: window.__minEnhance.state.videos.has(document.getElementById('x')) }))
    check('cross-site video: not picked up again (no new GPU context) on each page change', b.gl === gl0 && !b.otherAttached, { newContexts: b.gl - gl0, otherAttached: b.otherAttached })
    await p.close()
  }

  // ---- 2a) a paused video gets a new source and stays paused at the same time (0): the overlay must show the new picture
  for (const neural of [false, true]) {
    const p = await newPage('<body style="margin:0"><video id=v style="width:400px;height:225px;display:block"></video></body>', { fn: ortStub, arg: 0 })
    await p.goto('https://test.local/')
    await p.evaluate(fileVideo, { id: 'v', color: RED })
    await engine(p, { neural })
    await p.waitForFunction(() => { const st = window.__minEnhance.state.videos.get(document.getElementById('v')); return st && st.drawn }, null, { timeout: 10000 }).catch(() => {})
    await p.waitForTimeout(1000)
    const a = await p.evaluate(overlay, 'v')
    await p.evaluate(fileVideo, { id: 'v', color: LIME }) // same element, another clip, left paused at 0
    await p.waitForTimeout(1500)
    const b = await p.evaluate(overlay, 'v')
    check(`paused, new source (${neural ? 'neural' : 'shader'} path): overlay shows the new picture`, isRed(a.px) && isLime(b.px), { before: a.px, after: b.px })
    await p.close()
  }

  // ---- 2b) two paused videos at the same time and size: each overlay shows its own picture, and the model then rests
  {
    const p = await newPage('<body style="margin:0"><video id=a style="width:400px;height:225px;display:block"></video><video id=b style="width:400px;height:225px;display:block"></video></body>', { fn: ortStub, arg: 0 })
    await p.goto('https://test.local/')
    await p.evaluate(fileVideo, { id: 'a', color: RED })
    await p.evaluate(fileVideo, { id: 'b', color: LIME })
    await engine(p, { neural: true })
    await p.waitForFunction(() => ['a', 'b'].every(id => { const st = window.__minEnhance.state.videos.get(document.getElementById(id)); return st && st.nnId }), null, { timeout: 10000 }).catch(() => {})
    await p.waitForTimeout(1000)
    const a = await p.evaluate(overlay, 'a'); const b = await p.evaluate(overlay, 'b'); const runs0 = await p.evaluate(() => window.__runs)
    check('two paused videos: each overlay shows its own picture', isRed(a.px) && isLime(b.px), { a: a.px, b: b.px })
    await p.waitForTimeout(3000)
    const runs1 = await p.evaluate(() => window.__runs)
    check('two paused videos: the model rests (no turn-taking runs)', runs1 === runs0, { runsIn3s: runs1 - runs0 })
    await p.close()
  }

  // ---- 3) fullscreen
  {
    const p = await newPage('<body style="margin:0"><div id=box style="width:420px"><video id=v style="width:400px;height:225px;display:block"></video></div><video id=o style="width:400px;height:225px;display:block"></video><button id=fs1>video</button><button id=fs2>box</button></body>')
    await p.goto('https://test.local/')
    await p.evaluate(fileVideo, { id: 'v', color: RED, play: true })
    await p.evaluate(fileVideo, { id: 'o', color: LIME, play: true })
    await engine(p, { neural: false })
    await p.evaluate(() => { document.getElementById('fs1').onclick = () => document.getElementById('v').requestFullscreen(); document.getElementById('fs2').onclick = () => document.getElementById('box').requestFullscreen() })
    await p.waitForTimeout(1000)
    const centre = async () => { // what is on the screen in the middle of the film
      const png = (await p.screenshot()).toString('base64')
      return p.evaluate(async (b64) => {
        const r = document.getElementById('v').getBoundingClientRect()
        const i = new Image(); i.src = 'data:image/png;base64,' + b64; await i.decode(); const c = document.createElement('canvas'); c.width = i.width; c.height = i.height; const x = c.getContext('2d'); x.drawImage(i, 0, 0)
        return Array.from(x.getImageData(r.left + r.width / 2, r.top + r.height / 2, 1, 1).data).slice(0, 3)
      }, png)
    }
    await p.click('#fs1'); await p.waitForTimeout(1200)
    const a = await centre()
    check('the <video> itself in fullscreen: the film is on screen (not black)', isRed(a), a)
    await p.evaluate(() => document.exitFullscreen()); await p.waitForTimeout(800)
    await p.click('#fs2'); await p.waitForTimeout(1200)
    const b = await centre(); const o = await p.evaluate(overlay, 'o')
    check('player box in fullscreen: its film shown, the overlay of a video outside it is hidden', isRed(b) && o.display !== 'block', { centre: b, outsideOverlay: o.display })
    await p.evaluate(() => document.exitFullscreen()); await p.waitForTimeout(800)
    await p.close()
  }

  // ---- 4) the site keeps its video at opacity 0 until it plays, then fades it in
  {
    const p = await newPage('<body style="margin:0"><video id=v style="width:400px;height:225px;display:block;opacity:0"></video></body>')
    await p.goto('https://test.local/')
    await p.evaluate(fileVideo, { id: 'v', color: RED })
    await engine(p, { neural: false })
    await p.waitForTimeout(800) // the paused video is overlaid (hidden) before it plays
    await p.evaluate(() => { const v = document.getElementById('v'); v.addEventListener('playing', () => { v.style.opacity = '1' }); v.play() })
    await p.waitForTimeout(1000)
    await p.evaluate(() => window.__minEnhanceSet({ target: 80 })) // the source is now as sharp as needed: no overlay
    await p.waitForTimeout(800)
    const a = await p.evaluate(overlay, 'v')
    check('overlay gone: the page video is visible with the site\'s own opacity', a.display !== 'block' && a.opacity === '1', a)
    await p.close()
  }

  // ---- 5) and 6) images
  {
    const p = await browser.newPage({ viewport: { width: 800, height: 900 } })
    const png = async (see, color) => Buffer.from(await p.evaluate(([see, color]) => { const c = document.createElement('canvas'); c.width = 100; c.height = 60; const x = c.getContext('2d'); x.fillStyle = color; if (see) x.fillRect(30, 15, 40, 30); else x.fillRect(0, 0, 100, 60); return c.toDataURL('image/png').split(',')[1] }, [see, color || '#c84']), 'base64')
    const logo = await png(true); const photo = await png(false); const blue = await png(false, '#36c')
    await p.route('https://test.local/*.png', r => r.fulfill({ contentType: 'image/png', body: r.request().url().includes('logo') ? logo : photo }))
    await p.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0;background:#fff"><img id=l src="/logo.png" style="width:300px;height:180px;display:block"><img id=p src="/photo.png" style="width:300px;height:180px;display:block"></body>' }))
    await p.goto('https://test.local/')
    await p.waitForFunction(() => document.getElementById('l').complete && document.getElementById('p').complete)
    await engine(p, { neural: false, images: true })
    await p.waitForTimeout(2000)
    const r = await p.evaluate(async () => {
      const read = async (i) => { await i.decode().catch(() => {}); const c = document.createElement('canvas'); c.width = i.naturalWidth; c.height = i.naturalHeight; const x = c.getContext('2d'); x.drawImage(i, 0, 0); return Array.from(x.getImageData(2, 2, 1, 1).data) }
      const l = document.getElementById('l'); const ph = document.getElementById('p')
      return { logoEnhanced: !!l.dataset.minEnhanced, logoCorner: await read(l), photoEnhanced: !!ph.dataset.minEnhanced }
    })
    check('see-through image: left as it is (no black background)', !r.logoEnhanced && r.logoCorner[3] === 0, r)
    check('opaque image of the same site: still enhanced', r.photoEnhanced, r)
    await p.close()

    // the site swaps the picture (orange placeholder -> blue real one) while the enhanced copy is being encoded. The new
    // picture may well be enhanced on a later scan; what must never be shown is the copy of the old one.
    const q = await browser.newPage({ viewport: { width: 800, height: 900 } })
    await q.addInitScript(() => {
      const tb = HTMLCanvasElement.prototype.toBlob
      HTMLCanvasElement.prototype.toBlob = function (cb, ...a) { const i = document.getElementById('i'); if (i && !window.__swapped) { window.__swapped = true; i.src = '/real.png' } return tb.call(this, cb, ...a) }
    })
    await q.route('https://test.local/*.png', r => r.fulfill({ contentType: 'image/png', body: r.request().url().includes('real') ? blue : photo }))
    await q.route('https://test.local/', r => r.fulfill({ contentType: 'text/html', body: '<body style="margin:0"><img id=i src="/placeholder.png" style="width:300px;height:180px;display:block"></body>' }))
    await q.goto('https://test.local/')
    await q.waitForFunction(() => document.getElementById('i').complete)
    await engine(q, { neural: false, images: true })
    await q.waitForTimeout(1500)
    await q.evaluate(() => document.body.appendChild(document.createElement('div'))) // a page change: the next scan
    await q.waitForTimeout(1500)
    const s = await q.evaluate(async () => {
      const i = document.getElementById('i'); await i.decode().catch(() => {})
      const c = document.createElement('canvas'); c.width = i.naturalWidth; c.height = i.naturalHeight; const x = c.getContext('2d'); x.drawImage(i, 0, 0)
      return { swapped: !!window.__swapped, src: i.src.slice(0, 40), px: Array.from(x.getImageData(c.width / 2 | 0, c.height / 2 | 0, 1, 1).data).slice(0, 3) }
    })
    check('picture swapped during encoding: the new picture is shown, not the copy of the old one', s.swapped && s.px[2] > 150 && s.px[0] < 100, s)
    check('... and the new picture is enhanced on a later scan', s.src.startsWith('blob:'), s.src)
    await q.close()
  }
  await browser.close()
})
