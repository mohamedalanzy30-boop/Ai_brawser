/* Local (on-device) upscaling for <video> and <img> elements.
   Uses a WebGL2 shader (Catmull-Rom bicubic + contrast-adaptive sharpening),
   so nothing is downloaded and no extra bandwidth is used. */
;(function () {
  const ipcR = require('electron').ipcRenderer
  const cfg = { enabled: false, target: 1440, sharpness: 0.6, images: true, neural: true }
  const videoStates = new WeakMap()
  const activeVideos = new Set()
  let failed = false

  const VS = `#version 300 es
in vec2 p; out vec2 uv;
void main(){ uv = p*0.5+0.5; uv.y = 1.0-uv.y; gl_Position = vec4(p,0.,1.); }`

  const FS = `#version 300 es
precision highp float;
uniform sampler2D tex; uniform vec2 texSize; uniform float sharp;
in vec2 uv; out vec4 o;
vec4 catmull(vec2 c){
  vec2 pos = c*texSize; vec2 t = floor(pos-0.5)+0.5; vec2 f = pos-t;
  vec2 w0=f*(-0.5+f*(1.0-0.5*f)); vec2 w1=1.0+f*f*(-2.5+1.5*f);
  vec2 w2=f*(0.5+f*(2.0-1.5*f)); vec2 w3=f*f*(-0.5+0.5*f);
  vec2 s0=w0+w1; vec2 s1=w2+w3; vec2 f0=w1/s0; vec2 f1=w3/s1;
  vec2 t0=(t-1.0+f0)/texSize; vec2 t1=(t+1.0+f1)/texSize;
  return (texture(tex,vec2(t0.x,t0.y))*s0.x+texture(tex,vec2(t1.x,t0.y))*s1.x)*s0.y
       + (texture(tex,vec2(t0.x,t1.y))*s0.x+texture(tex,vec2(t1.x,t1.y))*s1.x)*s1.y;
}
void main(){
  vec4 c = catmull(uv);
  // contrast-adaptive sharpening on the upscaled neighbourhood
  vec2 px = 1.0/texSize*0.75;
  vec3 a=catmull(uv+vec2(0.,-px.y)).rgb, b=catmull(uv+vec2(-px.x,0.)).rgb,
       d=catmull(uv+vec2(px.x,0.)).rgb, e=catmull(uv+vec2(0.,px.y)).rgb;
  vec3 mn=min(min(a,b),min(d,min(e,c.rgb))), mx=max(max(a,b),max(d,max(e,c.rgb)));
  vec3 amp=sqrt(clamp(min(mn,1.0-mx)/max(mx,1e-4),0.,1.));
  vec3 wv=-amp*mix(0.125,0.2,sharp);
  vec3 outc=((a+b+d+e)*wv+c.rgb)/(1.0+4.0*wv);
  o=vec4(clamp(outc,0.,1.),1.);
}`

  function makeRenderer (canvas) {
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, preserveDrawingBuffer: true, powerPreference: 'low-power' })
    if (!gl) return null
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s }
    const prog = gl.createProgram()
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FS))
    gl.linkProgram(prog)
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return null
    gl.useProgram(prog)
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    const loc = gl.getAttribLocation(prog, 'p'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    const tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    const uSize = gl.getUniformLocation(prog, 'texSize'); const uSharp = gl.getUniformLocation(prog, 'sharp')
    return {
      draw (source, w, h) { // throws SecurityError for tainted (cross-origin) sources
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
        gl.viewport(0, 0, canvas.width, canvas.height)
        gl.uniform2f(uSize, w, h); gl.uniform1f(uSharp, cfg.sharpness)
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      },
      drawRaw (bytes, w, h, sharp) { // RGBA bytes (neural output)
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes)
        gl.viewport(0, 0, canvas.width, canvas.height)
        gl.uniform2f(uSize, w, h); gl.uniform1f(uSharp, sharp)
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
      },
      dispose () { const e = gl.getExtension('WEBGL_lose_context'); if (e) e.loseContext() }
    }
  }

  /* ---------- neural service (ONNX Runtime Web in a hidden window) ---------- */
  const nn = { port: null, state: 'idle', backend: null, pending: false, ms: 0, last: null, id: 0, grab: null }

  function nnConnect () {
    if (nn.port) return
    const ch = new window.MessageChannel()
    nn.port = ch.port2
    nn.state = 'connecting'
    nn.port.onmessage = function (e) {
      const m = e.data
      if (m.type === 'status') { nn.state = m.state; nn.backend = m.backend || nn.backend }
      else if (m.type === 'result') {
        nn.pending = false
        nn.ms = nn.ms ? nn.ms * 0.7 + m.ms * 0.3 : m.ms
        nn.last = { mediaTime: m.mediaTime, w: m.w, h: m.h, bytes: new Uint8Array(m.data) }
      }
      else if (m.type === 'error') { nn.pending = false; nn.state = 'error' }
    }
    ipcR.postMessage('upscale-session-create', null, [ch.port1])
    nn.port.postMessage({ type: 'warmup' })
  }

  function nnSubmit (video, vw, vh) {
    if (!nn.grab) { nn.grab = document.createElement('canvas'); nn.grab.ctx = nn.grab.getContext('2d', { willReadFrequently: true }) }
    if (nn.grab.width !== vw || nn.grab.height !== vh) { nn.grab.width = vw; nn.grab.height = vh }
    nn.grab.ctx.drawImage(video, 0, 0, vw, vh)
    const img = nn.grab.ctx.getImageData(0, 0, vw, vh) // throws for tainted video
    nn.pending = true
    nn.port.postMessage({ type: 'frame', id: ++nn.id, mediaTime: video.currentTime, w: vw, h: vh, data: img.data.buffer }, [img.data.buffer])
  }

  /* ---------- video ---------- */
  function outputSize (vw, vh, rect) {
    // never render above the target height, nor above the on-screen pixel size
    const dpr = window.devicePixelRatio || 1
    let h = Math.min(cfg.target, Math.round(rect.height * dpr))
    let w = Math.round(h * rect.width / Math.max(rect.height, 1))
    if (h <= vh) { h = vh; w = vw } // already at/above screen size: no benefit
    return [Math.max(w, 2), Math.max(h, 2)]
  }

  function attachVideo (video) {
    if (videoStates.has(video) || failed) return
    const canvas = document.createElement('canvas')
    canvas.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;display:none'
    canvas.setAttribute('data-min-upscaler', '1')
    const renderer = makeRenderer(canvas)
    if (!renderer) { failed = true; return }
    const st = { canvas, renderer, running: false, prevOpacity: video.style.opacity, errors: 0 }
    videoStates.set(video, st)
    activeVideos.add(video)
    document.documentElement.appendChild(canvas)
    video.addEventListener('play', () => loop(video))
    loop(video)
  }

  function detachVideo (video) {
    const st = videoStates.get(video)
    if (!st) return
    st.canvas.remove(); st.renderer.dispose()
    video.style.opacity = st.prevOpacity
    videoStates.delete(video); activeVideos.delete(video)
  }

  function loop (video) {
    const st = videoStates.get(video)
    if (!st || st.running) return
    st.running = true
    const tick = () => {
      if (!videoStates.has(video) || !cfg.enabled) { st.running = false; return }
      const rect = video.getBoundingClientRect()
      const vw = video.videoWidth; const vh = video.videoHeight
      const visible = vw > 0 && video.readyState >= 2 && rect.width > 40 && rect.height > 40 &&
        rect.bottom > 0 && rect.top < window.innerHeight && !document.hidden
      if (visible && vh < Math.min(cfg.target, rect.height * (window.devicePixelRatio || 1)) * 0.95) {
        const [w, h] = outputSize(vw, vh, rect)
        if (st.canvas.width !== w || st.canvas.height !== h) { st.canvas.width = w; st.canvas.height = h }
        const s = st.canvas.style
        s.display = 'block'; s.left = rect.left + 'px'; s.top = rect.top + 'px'; s.width = rect.width + 'px'; s.height = rect.height + 'px'
        try {
          const useNN = cfg.neural && vh <= 540 && nn.state !== 'error'
          if (useNN) {
            nnConnect()
            // while playing, only keep submitting frames if the model is fast enough
            const fast = nn.ms === 0 || nn.ms < 140 || video.paused
            if (nn.state === 'ready' && !nn.pending && fast) nnSubmit(video, vw, vh)
          }
          const r = nn.last
          if (useNN && r && r.w === vw * 4 && Math.abs(r.mediaTime - video.currentTime) < 0.15) {
            st.renderer.drawRaw(r.bytes, r.w, r.h, cfg.sharpness * 0.3) // neural frame
          } else {
            st.renderer.draw(video, vw, vh) // instant shader fallback while the model warms up / catches up
          }
          video.style.opacity = '0'
          st.errors = 0
        } catch (e) { // cross-origin video without CORS: give up on this one
          if (++st.errors > 3) { detachVideo(video); st.running = false; return }
        }
      } else {
        st.canvas.style.display = 'none'; video.style.opacity = st.prevOpacity
      }
      if (video.requestVideoFrameCallback && !video.paused) video.requestVideoFrameCallback(tick)
      else setTimeout(() => requestAnimationFrame(tick), video.paused ? 250 : 16)
    }
    tick()
  }

  /* ---------- images ---------- */
  const doneImages = new WeakSet()
  function upscaleImage (img) {
    if (doneImages.has(img) || !img.complete || !img.naturalWidth || img.dataset.minUpscaled) return
    const rect = img.getBoundingClientRect()
    const dpr = window.devicePixelRatio || 1
    const wantW = Math.min(rect.width * dpr, cfg.target * (img.naturalWidth / img.naturalHeight))
    if (rect.width < 80 || img.naturalWidth >= wantW * 0.9 || img.naturalWidth * img.naturalHeight > 4e6) return
    doneImages.add(img)
    try {
      const scale = Math.min(4, Math.ceil(wantW / img.naturalWidth))
      const canvas = document.createElement('canvas')
      canvas.width = img.naturalWidth * scale; canvas.height = img.naturalHeight * scale
      const r = makeRenderer(canvas)
      if (!r) return
      r.draw(img, img.naturalWidth, img.naturalHeight)
      canvas.toBlob(function (blob) {
        r.dispose()
        if (!blob) return
        img.dataset.minUpscaled = '1'; img.removeAttribute('srcset'); img.src = URL.createObjectURL(blob)
      }, 'image/webp', 0.92)
    } catch (e) { /* tainted cross-origin image: skip */ }
  }

  /* ---------- orchestration ---------- */
  function scan () {
    if (!cfg.enabled || failed) return
    document.querySelectorAll('video').forEach(attachVideo)
    if (cfg.images) document.querySelectorAll('img').forEach(upscaleImage)
  }
  let scanTimer = null
  new window.MutationObserver(() => { clearTimeout(scanTimer); scanTimer = setTimeout(scan, 400) })
    .observe(document, { childList: true, subtree: true })

  function apply (c) {
    Object.assign(cfg, c || {})
    if (cfg.enabled) { scan(); activeVideos.forEach(loop) } else { Array.from(activeVideos).forEach(detachVideo) }
  }
  ipcR.on('upscaleConfig', (e, c) => apply(c))
  document.addEventListener('DOMContentLoaded', () => ipcR.send('upscaleGetConfig'))
  document.addEventListener('visibilitychange', () => { if (!document.hidden) activeVideos.forEach(loop) })
})()
