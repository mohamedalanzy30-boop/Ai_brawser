/* On-device video/image enhancement engine (runs inside the page, no network needed).
   Pipeline per video frame, all on the GPU (WebGL2):
     [optional] neural 4x super-resolution (Real-ESRGAN general-x4v3 via ONNX Runtime Web, WebGPU)
     temporal stabilisation -> frame interpolation (block-matching flow) ->
     Catmull-Rom resample -> denoise/deblock -> contrast-adaptive sharpen -> adaptive tone mapping.
   Native code sets configuration with window.__minEnhanceSet({...}). */
;(function () {
  if (window.__minEnhance) return
  const HOST = 'https://appassets.min.local/'
  const cfg = {
    enabled: true, target: 1440, sharpness: 0.6, neural: true, denoise: 0.35,
    tone: true, temporal: true, interp: false, interpNeural: true, images: true
  }
  Object.assign(cfg, window.__minCfg0 || {})
  const nativeShort = () => Math.round(Math.min(screen.width, screen.height) * (window.devicePixelRatio || 1))
  const effTarget = () => cfg.target || nativeShort()
  try { // WebView: screen.orientation.lock() rejects, which breaks many players' fullscreen; native code rotates instead
    if (screen.orientation) { screen.orientation.lock = () => Promise.resolve(); screen.orientation.unlock = () => {} }
  } catch (e) { /* ignore */ }
  const guard = { noInterp: false, noNeural: false }
  const longTasks = []
  try { new PerformanceObserver(l => { for (const e of l.getEntries()) longTasks.push([e.startTime, e.duration]) }).observe({ entryTypes: ['longtask'] }) } catch (e) { /* unsupported */ }
  setInterval(() => {
    const now = performance.now()
    while (longTasks.length && now - longTasks[0][0] > 3000) longTasks.shift()
    const busy = longTasks.reduce((a, t) => a + t[1], 0)
    const playing = Array.from(state.videos.keys()).some(v => !v.paused)
    if (!playing || busy < 400) return
    if (cfg.interp && !guard.noInterp) { guard.noInterp = true; longTasks.length = 0; log('performance guard: frame interpolation turned off (main thread busy ' + Math.round(busy) + 'ms/3s)') }
    else if (cfg.neural && !guard.noNeural) { guard.noNeural = true; longTasks.length = 0; log('performance guard: neural upscaling turned off while playing (busy ' + Math.round(busy) + 'ms/3s)') }
  }, 2000)
  const log = (...a) => { try { console.warn('[AIB]', ...a) } catch (e) { /* ignore */ } }
  const state = { videos: new Map(), failed: false, nn: null, stats: { nn: 'idle', fps: 0, ms: 0 } }

  /* ---------------- shaders ---------------- */
  const VS = `#version 300 es
in vec2 p; out vec2 uv;
void main(){ uv = p*0.5+0.5; uv.y = 1.0-uv.y; gl_Position = vec4(p,0.,1.); }`

  // vertex shader for render-to-texture passes: no vertical flip, so intermediate textures keep the
  // same orientation as uploaded video frames (row 0 = top)
  const VS_FBO = `#version 300 es
in vec2 p; out vec2 uv;
void main(){ uv = p*0.5+0.5; gl_Position = vec4(p,0.,1.); }`

  // stabilise: motion-adaptive temporal blend of previous and current source frame
  const FS_STAB = `#version 300 es
precision highp float; uniform sampler2D cur, prev; uniform float amt; in vec2 uv; out vec4 o;
void main(){ vec3 c=texture(cur,uv).rgb, p=texture(prev,uv).rgb;
  float d=length(c-p); float a=amt*(1.0-smoothstep(0.07,0.22,d)); o=vec4(mix(c,p,a),1.); }`

  // flow: block matching at reduced resolution, output = displacement (source uv units) + sad
  const FS_FLOW = `#version 300 es
precision highp float; uniform sampler2D cur, prev; uniform vec2 srcSize; in vec2 uv; out vec4 o;
float lum(vec3 c){ return dot(c, vec3(.299,.587,.114)); }
void main(){
  vec2 px = 4.0/srcSize; float best=1e9; vec2 bd=vec2(0.);
  for(int y=-6;y<=6;y++) for(int x=-6;x<=6;x++){
    vec2 d=vec2(float(x),float(y))*px*0.5; float s=0.;
    for(int j=-1;j<=1;j++) for(int i=-1;i<=1;i++){
      vec2 o2=vec2(float(i),float(j))*px*0.5;
      s+=abs(lum(texture(cur,uv+o2).rgb)-lum(texture(prev,uv+o2+d).rgb)); }
    s+=0.002*length(vec2(x,y)); // prefer small motion
    if(s<best){best=s;bd=d;} }
  o=vec4(bd,best,1.); }`

  // midpoint frame between prev and cur using the flow field
  const FS_MID = `#version 300 es
precision highp float; uniform sampler2D cur, prev, flow; in vec2 uv; out vec4 o;
void main(){ vec4 f=texture(flow,uv); vec2 d=f.xy; float ok=1.0-smoothstep(0.35,0.9,f.z);
  vec3 a=texture(cur,uv-d*0.5*ok).rgb, b=texture(prev,uv+d*0.5*ok).rgb; o=vec4(mix(a,b,0.5),1.); }`

  const FS_MAIN = `#version 300 es
precision highp float;
uniform sampler2D tex; uniform vec2 texSize; uniform float sharp, denoise;
uniform vec4 tone; // lo, hi, contrast, saturation ; hi<=lo => off
uniform float gammaV;
in vec2 uv; out vec4 o;
vec4 catmull(vec2 c){
  vec2 pos=c*texSize; vec2 t=floor(pos-0.5)+0.5; vec2 f=pos-t;
  vec2 w0=f*(-0.5+f*(1.0-0.5*f)); vec2 w1=1.0+f*f*(-2.5+1.5*f);
  vec2 w2=f*(0.5+f*(2.0-1.5*f)); vec2 w3=f*f*(-0.5+0.5*f);
  vec2 s0=w0+w1; vec2 s1=w2+w3; vec2 f0=w1/s0; vec2 f1=w3/s1;
  vec2 t0=(t-1.0+f0)/texSize; vec2 t1=(t+1.0+f1)/texSize;
  return (texture(tex,vec2(t0.x,t0.y))*s0.x+texture(tex,vec2(t1.x,t0.y))*s1.x)*s0.y
       + (texture(tex,vec2(t0.x,t1.y))*s0.x+texture(tex,vec2(t1.x,t1.y))*s1.x)*s1.y; }
void main(){
  vec4 c=catmull(uv); vec2 px=0.75/texSize;
  vec3 a=catmull(uv+vec2(0.,-px.y)).rgb, b=catmull(uv+vec2(-px.x,0.)).rgb,
       d=catmull(uv+vec2(px.x,0.)).rgb, e=catmull(uv+vec2(0.,px.y)).rgb;
  // edge-preserving denoise / deblock: pull towards neighbours that are similar to the centre
  vec3 acc=c.rgb; float wsum=1.0; float k=denoise*28.0;
  vec3 n[4]=vec3[4](a,b,d,e);
  for(int i=0;i<4;i++){ float w=exp(-k*dot(n[i]-c.rgb,n[i]-c.rgb)*8.0); acc+=n[i]*w; wsum+=w; }
  vec3 base=mix(c.rgb,acc/wsum,clamp(denoise*1.6,0.,1.));
  vec3 mn=min(min(a,b),min(d,min(e,base))), mx=max(max(a,b),max(d,max(e,base)));
  vec3 amp=sqrt(clamp(min(mn,1.0-mx)/max(mx,1e-4),0.,1.));
  vec3 wv=-amp*mix(0.125,0.2,sharp);
  vec3 r=((a+b+d+e)*wv+base)/(1.0+4.0*wv);
  if(tone.y>tone.x){ // adaptive tone mapping (SDR -> HDR-like)
    r=clamp((r-tone.x)/(tone.y-tone.x),0.,1.); r=pow(r,vec3(gammaV));
    r=mix(r, r*r*(3.0-2.0*r), tone.z);
    float l=dot(r,vec3(.2126,.7152,.0722)); r=mix(vec3(l),r,tone.w); }
  o=vec4(clamp(r,0.,1.),1.); }`

  function compile (gl, type, src) {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s)
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s))
    return s
  }
  function program (gl, fs, vs) {
    const p = gl.createProgram()
    gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, vs || VS)); gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, fs))
    gl.bindAttribLocation(p, 0, 'p'); gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p))
    const u = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS)
    for (let i = 0; i < n; i++) { const nm = gl.getActiveUniform(p, i).name; u[nm] = gl.getUniformLocation(p, nm) }
    return { p, u }
  }
  function texture (gl, w, h, filter) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t)
    const f = filter || gl.LINEAR
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, f); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, f)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    if (w) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
    return t
  }

  /* ---------------- renderer (one per video / image) ---------------- */
  function makeRenderer (canvas) {
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' })
    if (!gl) return null
    const R = { gl, canvas, w: 0, h: 0 }
    R.stab = program(gl, FS_STAB, VS_FBO); R.flowP = program(gl, FS_FLOW, VS_FBO); R.mid = program(gl, FS_MID, VS_FBO); R.main = program(gl, FS_MAIN)
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
    R.texCur = texture(gl); R.texPrev = texture(gl); R.texNN = texture(gl)
    R.fbo = gl.createFramebuffer(); R.hasPrev = false
    R.resize = function (w, h) { // source-sized intermediates
      if (R.w === w && R.h === h) return
      R.w = w; R.h = h
      R.texStab = texture(gl, w, h); R.texStabPrev = texture(gl, w, h); R.hasStab = false; R.texMid = texture(gl, w, h)
      R.fw = Math.max(8, Math.round(w / 4)); R.fh = Math.max(8, Math.round(h / 4))
      R.texFlow = texture(gl, R.fw, R.fh, gl.NEAREST)
      gl.bindTexture(gl.TEXTURE_2D, R.texFlow)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, R.fw, R.fh, 0, gl.RGBA, gl.FLOAT, null)
      R.hasPrev = false
    }
    R.bind = function (unit, tex) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex) }
    R.pass = function (prog, target, w, h, setup) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, target ? R.fbo : null)
      if (target) gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0)
      gl.viewport(0, 0, w, h); gl.useProgram(prog.p); setup(prog.u); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
    }
    R.upload = function (source) { // rotate cur -> prev, upload new frame into cur (throws on tainted media)
      const t = R.texPrev; R.texPrev = R.texCur; R.texCur = t
      R.bind(0, R.texCur)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
    }
    R.uploadRaw = function (bytes, w, h) {
      R.bind(0, R.texNN); gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes)
    }
    // which: 'cur' | 'mid' | 'nn'
    R.draw = function (which, vw, vh, tone, sharp) {
      let src = R.texCur; let sw = vw; let sh = vh
      if (which === 'nn') { src = R.texNN; sw = vw * 4; sh = vh * 4 } else if (which === 'raw') { src = R.texNN } else {
        if (cfg.temporal && R.hasPrev) {
          // recursive filter: blend the new frame with the previous *filtered* frame (stronger than a 2-frame blend)
          R.pass(R.stab, R.texStab, vw, vh, u => { R.bind(0, R.texCur); R.bind(1, R.hasStab ? R.texStabPrev : R.texPrev); gl.uniform1i(u.cur, 0); gl.uniform1i(u.prev, 1); gl.uniform1f(u.amt, 0.78) })
          src = R.texStab
          const sw2 = R.texStab; R.texStab = R.texStabPrev; R.texStabPrev = sw2; R.hasStab = true
        }
        if (which === 'mid' && R.hasPrev) {
          R.pass(R.flowP, R.texFlow, R.fw, R.fh, u => { R.bind(0, R.texCur); R.bind(1, R.texPrev); gl.uniform1i(u.cur, 0); gl.uniform1i(u.prev, 1); gl.uniform2f(u.srcSize, vw, vh) })
          R.pass(R.mid, R.texMid, vw, vh, u => { R.bind(0, R.texCur); R.bind(1, R.texPrev); R.bind(2, R.texFlow); gl.uniform1i(u.cur, 0); gl.uniform1i(u.prev, 1); gl.uniform1i(u.flow, 2) })
          src = R.texMid
        }
      }
      R.pass(R.main, null, R.canvas.width, R.canvas.height, u => {
        R.bind(0, src); gl.uniform1i(u.tex, 0); gl.uniform2f(u.texSize, sw, sh)
        gl.uniform1f(u.sharp, sharp); gl.uniform1f(u.denoise, which === 'nn' ? cfg.denoise * 0.3 : cfg.denoise)
        gl.uniform4f(u.tone, tone.lo, tone.hi, tone.contrast, tone.sat); gl.uniform1f(u.gammaV, tone.gamma)
      })
      R.hasPrev = true
    }
    R.dispose = function () { const e = gl.getExtension('WEBGL_lose_context'); if (e) e.loseContext() }
    gl.getExtension('EXT_color_buffer_float')
    return R
  }

  /* ---------------- adaptive tone analysis ---------------- */
  const toneCanvas = document.createElement('canvas'); toneCanvas.width = 32; toneCanvas.height = 18
  const toneCtx = toneCanvas.getContext('2d', { willReadFrequently: true })
  function analyseTone (st, video) {
    toneCtx.drawImage(video, 0, 0, 32, 18)
    const d = toneCtx.getImageData(0, 0, 32, 18).data; const ls = []
    for (let i = 0; i < d.length; i += 4) ls.push((0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255)
    ls.sort((a, b) => a - b)
    const lo = ls[Math.floor(ls.length * 0.02)]; const hi = ls[Math.floor(ls.length * 0.98)]
    const mean = ls.reduce((a, b) => a + b, 0) / ls.length
    const gamma = Math.min(1.25, Math.max(0.8, Math.log(0.45) / Math.log(Math.min(0.9, Math.max(0.1, (mean - lo) / Math.max(hi - lo, 0.05))))))
    const t = st.tone; const k = 0.12 // smooth to avoid flicker
    t.lo += (lo * 0.9 - t.lo) * k; t.hi += (Math.max(hi, lo + 0.2) - t.hi) * k; t.gamma += (gamma - t.gamma) * k
  }

  /* ---------------- neural 4x via ONNX Runtime Web (in page) ---------------- */
  function nnInit () {
    if (state.nn || !window.ort) { if (!window.ort) state.stats.nn = 'ort-missing'; return }
    const nn = state.nn = { session: null, busy: false, last: null, ms: 0, id: 0 }
    state.stats.nn = 'loading'
    const ort = window.ort
    ort.env.wasm.wasmPaths = HOST + 'ort/'; ort.env.wasm.numThreads = 1
    ;(async () => {
      const bytes = new Uint8Array(await (await fetch(HOST + 'model/realesr-general-x4v3.onnx')).arrayBuffer())
      let hasGpu = false
      try { hasGpu = !!(navigator.gpu && await navigator.gpu.requestAdapter()) } catch (e) { /* none */ }
      for (const ep of hasGpu ? ['webgpu', 'wasm'] : ['wasm']) {
        try { nn.session = await ort.InferenceSession.create(bytes, { executionProviders: [ep], graphOptimizationLevel: 'all' }); nn.backend = ep; break } catch (e) { log('SR backend', ep, 'failed:', String(e)) }
      }
      state.stats.nn = nn.session ? 'ready:' + nn.backend : 'error'
      log('neural SR model', state.stats.nn)
    })().catch((e) => { state.stats.nn = 'error'; log('neural SR load failed:', String(e)) })
  }
  const grab = document.createElement('canvas'); const grabCtx = grab.getContext('2d', { willReadFrequently: true })
  function grabBytes (video, vw, vh) {
    grab.width = vw; grab.height = vh; grabCtx.drawImage(video, 0, 0, vw, vh)
    return grabCtx.getImageData(0, 0, vw, vh).data
  }
  async function nnRun (video, vw, vh) {
    const nn = state.nn; nn.busy = true
    try {
      grab.width = vw; grab.height = vh; grabCtx.drawImage(video, 0, 0, vw, vh)
      const d = grabCtx.getImageData(0, 0, vw, vh).data; const px = vw * vh; const inp = new Float32Array(3 * px)
      for (let i = 0, j = 0; i < px; i++, j += 4) { inp[i] = d[j] / 255; inp[px + i] = d[j + 1] / 255; inp[2 * px + i] = d[j + 2] / 255 }
      const mt = video.currentTime; const t0 = performance.now()
      const out = await nn.session.run({ [nn.session.inputNames[0]]: new window.ort.Tensor('float32', inp, [1, 3, vh, vw]) })
      nn.ms = nn.ms ? nn.ms * 0.7 + (performance.now() - t0) * 0.3 : performance.now() - t0
      const o = out[nn.session.outputNames[0]].data; const ow = vw * 4; const oh = vh * 4; const opx = ow * oh
      const res = new Uint8Array(opx * 4)
      for (let i = 0, j = 0; i < opx; i++, j += 4) { res[j] = o[i] * 255 + 0.5; res[j + 1] = o[opx + i] * 255 + 0.5; res[j + 2] = o[2 * opx + i] * 255 + 0.5; res[j + 3] = 255 }
      nn.last = { mediaTime: mt, w: ow, h: oh, bytes: res }
    } catch (e) { state.stats.nn = 'error'; log('SR inference failed:', String(e)) } finally { nn.busy = false }
  }

  /* ---------------- neural frame interpolation: RIFE 4.25 lite (MIT) ---------------- */
  const rife = { session: null, loading: false, busy: false, ms: 0, backend: null }
  function rifeInit () {
    if (rife.session || rife.loading || !window.ort) return
    rife.loading = true
    const ort = window.ort
    ort.env.wasm.wasmPaths = HOST + 'ort/'; ort.env.wasm.numThreads = 1
    ;(async () => {
      const bytes = new Uint8Array(await (await fetch(HOST + 'model/rife_v425lite.onnx')).arrayBuffer())
      let hasGpu = false
      try { hasGpu = !!(navigator.gpu && await navigator.gpu.requestAdapter()) } catch (e) { /* none */ }
      for (const ep of hasGpu ? ['webgpu', 'wasm'] : ['wasm']) {
        try { rife.session = await ort.InferenceSession.create(bytes, { executionProviders: [ep], graphOptimizationLevel: 'all' }); rife.backend = ep; break } catch (e) { log('RIFE backend', ep, 'failed:', String(e)) }
      }
      state.stats.rife = rife.session ? 'ready:' + rife.backend : 'error'
      log('RIFE model', state.stats.rife)
    })().catch((e) => { state.stats.rife = 'error'; rife.loading = false; log('RIFE load failed:', String(e)) })
  }
  function toPlanes (rgba, w, h, pw, ph) { // RGBA bytes -> padded NCHW float32
    const pl = pw * ph; const out = new Float32Array(3 * pl)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = y * w + x; const j = i * 4; const o = y * pw + x
      out[o] = rgba[j] / 255; out[pl + o] = rgba[j + 1] / 255; out[2 * pl + o] = rgba[j + 2] / 255
    }
    return out
  }
  async function rifeMid (prev, cur, w, h) {
    const pw = Math.ceil(w / 64) * 64; const ph = Math.ceil(h / 64) * 64; const t0 = performance.now(); const ort = window.ort
    const out = await rife.session.run({
      img0: new ort.Tensor('float32', toPlanes(prev, w, h, pw, ph), [1, 3, ph, pw]),
      img1: new ort.Tensor('float32', toPlanes(cur, w, h, pw, ph), [1, 3, ph, pw]),
      timestep: new ort.Tensor('float32', new Float32Array([0.5]), [1, 1, 1, 1])
    })
    rife.ms = rife.ms ? rife.ms * 0.7 + (performance.now() - t0) * 0.3 : performance.now() - t0
    const d = out[rife.session.outputNames[0]].data; const pl = pw * ph; const res = new Uint8Array(w * h * 4)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const o = y * pw + x; const j = (y * w + x) * 4
      res[j] = d[o] * 255 + 0.5; res[j + 1] = d[pl + o] * 255 + 0.5; res[j + 2] = d[2 * pl + o] * 255 + 0.5; res[j + 3] = 255
    }
    return res
  }

  /* ---------------- video ---------------- */
  function outSize (vw, vh, rect) {
    const dpr = window.devicePixelRatio || 1
    let h = Math.min(effTarget(), Math.round(rect.height * dpr)); let w = Math.round(h * rect.width / Math.max(rect.height, 1))
    if (h <= vh) { h = vh; w = vw }
    return [Math.max(w, 2), Math.max(h, 2)]
  }
  function host () { return document.fullscreenElement || document.webkitFullscreenElement || document.documentElement }
  document.addEventListener('fullscreenchange', () => state.videos.forEach(st => host().appendChild(st.canvas)))
  document.addEventListener('webkitfullscreenchange', () => state.videos.forEach(st => host().appendChild(st.canvas)))
  function attach (video) {
    if (video.mediaKeys) { log('DRM video skipped'); return }
    if (state.videos.has(video) || state.failed) return
    const canvas = document.createElement('canvas')
    canvas.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;display:none'
    let R
    try { R = makeRenderer(canvas) } catch (e) { R = null; console.warn('enhance: shader error', e) }
    if (!R) { state.failed = true; log('WebGL2 unavailable'); return }
    const st = { canvas, R, running: false, prevOpacity: video.style.opacity, errors: 0, frame: 0, tone: { lo: 0, hi: 1, contrast: 0.22, sat: 1.1, gamma: 1 }, lastT: 0, fpsEMA: 30 }
    state.videos.set(video, st); host().appendChild(canvas)
    video.addEventListener('play', () => { st.fpsEMA = 30; st.lastT = 0; loop(video) }); loop(video)
  }
  function detach (video) {
    const st = state.videos.get(video); if (!st) return
    st.canvas.remove(); st.R.dispose(); video.style.opacity = st.prevOpacity; state.videos.delete(video)
  }
  function loop (video) {
    const st = state.videos.get(video); if (!st || st.running) return
    st.running = true
    const next = (cb) => {
      if (video.requestVideoFrameCallback && !video.paused) video.requestVideoFrameCallback(cb)
      else setTimeout(() => requestAnimationFrame(cb), video.paused ? 250 : 16)
    }
    const tick = (now, meta) => {
      if (video.mediaKeys) { detach(video); st.running = false; return }
      if (!state.videos.has(video) || !cfg.enabled) { st.running = false; return }
      const rect = video.getBoundingClientRect(); const vw = video.videoWidth; const vh = video.videoHeight; const dpr = window.devicePixelRatio || 1
      const wantH = Math.min(effTarget(), rect.height * dpr)
      const visible = vw > 0 && video.readyState >= 2 && rect.width > 40 && rect.height > 40 && rect.bottom > 0 && rect.top < window.innerHeight && !document.hidden
      const why = !visible ? 'hidden/not-ready' : (vh >= wantH * 0.95 ? 'source already >= target' : 'enhancing')
      if (st.why !== why) { st.why = why; log('video', vw + 'x' + vh, 'shown=' + Math.round(rect.width) + 'x' + Math.round(rect.height) + 'css', 'wantH=' + Math.round(wantH), '=>', why, 'frame=' + (window.top === window ? 'TOP' : 'FRAME')) }
      if (!(visible && vh < wantH * 0.95)) { st.canvas.style.display = 'none'; video.style.opacity = st.prevOpacity; next(tick); return }
      const [w, h] = outSize(vw, vh, rect)
      if (st.canvas.width !== w || st.canvas.height !== h) { st.canvas.width = w; st.canvas.height = h }
      const s = st.canvas.style; s.display = 'block'; s.left = rect.left + 'px'; s.top = rect.top + 'px'; s.width = rect.width + 'px'; s.height = rect.height + 'px'
      state.stats.fps = Math.round(st.fpsEMA)
      state.stats.active = Array.from(state.videos.values()).filter(x => x.canvas.style.display === 'block').length
      try {
        st.R.resize(vw, vh); st.R.upload(video); st.frame++
        if (cfg.tone && st.frame % 6 === 1) analyseTone(st, video)
        const tone = cfg.tone ? st.tone : { lo: 0, hi: 0, contrast: 0, sat: 1, gamma: 1 }
        const dt = st.lastT ? (now - st.lastT) : 33; st.lastT = now; st.fpsEMA = st.fpsEMA * 0.9 + (1000 / Math.max(dt, 1)) * 0.1
        const wantInterp = cfg.interp && !guard.noInterp && !video.paused && st.fpsEMA < 40
        if (wantInterp && cfg.interpNeural && vh <= 540 && !(rife.session && st.prevBytes && rife.ms < 0.9 * (1000 / st.fpsEMA))) st.prevBytes = grabBytes(video, vw, vh)
        const wantNN = cfg.neural && (!guard.noNeural || video.paused) && !wantInterp && vh <= 540 && state.nn && state.nn.session && state.stats.nn !== 'error'
        if (cfg.neural && vh <= 540) nnInit()
        // Neural frames are only shown when they are consistent: exact frame while paused, or when the
        // model keeps up with the video (always one steady frame late). Never mix stale and live frames.
        const nnS = state.nn; const T = 1000 / Math.max(st.fpsEMA, 1)
        const fast = !!nnS && nnS.ms > 0 && nnS.ms < 0.8 * T
        if (wantNN && !nnS.busy && (video.paused || fast || !nnS.ms)) { nnS.lastProbe = performance.now(); nnRun(video, vw, vh) }
        const r = nnS && nnS.last
        const fresh = wantNN && r && r.w === vw * 4 && (video.paused ? Math.abs(r.mediaTime - video.currentTime) < 0.05 : fast && Math.abs(r.mediaTime - video.currentTime) < 3 * T / 1000)
        if (fresh) {
          st.R.uploadRaw(r.bytes, r.w, r.h); st.R.draw('nn', vw, vh, tone, cfg.sharpness * 0.3)
        } else if (wantInterp && cfg.interpNeural && vh <= 540 && (rifeInit(), rife.session) && st.prevBytes &&
                   rife.ms < 0.9 * (1000 / st.fpsEMA)) {
          // neural midpoint: present it first, then the real frame half an interval later
          const cur = grabBytes(video, vw, vh); const prevB = st.prevBytes; st.prevBytes = cur
          if (!rife.busy) {
            rife.busy = true
            rifeMid(prevB, cur, vw, vh).then(mid => {
              st.R.uploadRaw(mid, vw, vh); st.R.draw('raw', vw, vh, tone, cfg.sharpness)
              setTimeout(() => { if (state.videos.has(video)) { try { st.R.draw('cur', vw, vh, tone, cfg.sharpness) } catch (e) { /* ignore */ } } }, Math.max(8, 500 / st.fpsEMA))
            }).catch((e) => { rife.ms = 1e9; log('RIFE inference failed:', String(e)) }).finally(() => { rife.busy = false })
          } else st.R.draw('cur', vw, vh, tone, cfg.sharpness)
        } else if (wantInterp && st.R.hasPrev) {
          st.R.draw('mid', vw, vh, tone, cfg.sharpness) // midpoint now, real frame half an interval later
          setTimeout(() => { if (state.videos.has(video)) { try { st.R.draw('cur', vw, vh, tone, cfg.sharpness) } catch (e) { /* ignore */ } } }, Math.max(8, 500 / st.fpsEMA))
        } else st.R.draw('cur', vw, vh, tone, cfg.sharpness)
        video.style.opacity = '0'; st.errors = 0
      } catch (e) { if (++st.errors > 3) { log('video disabled (', String(e), ') src=', (video.currentSrc || '').slice(0, 80)); detach(video); st.running = false; return } }
      next(tick)
    }
    tick(performance.now())
  }

  /* ---------------- images ---------------- */
  const doneImages = new WeakSet()
  function upscaleImage (img) {
    if (doneImages.has(img) || !img.complete || !img.naturalWidth || img.dataset.minEnhanced) return
    const rect = img.getBoundingClientRect(); const dpr = window.devicePixelRatio || 1
    const wantW = Math.min(rect.width * dpr, effTarget() * (img.naturalWidth / img.naturalHeight))
    if (rect.width < 80 || img.naturalWidth >= wantW * 0.9 || img.naturalWidth * img.naturalHeight > 4e6) return
    doneImages.add(img)
    try {
      const scale = Math.min(4, Math.ceil(wantW / img.naturalWidth)); const canvas = document.createElement('canvas')
      canvas.width = img.naturalWidth * scale; canvas.height = img.naturalHeight * scale
      const R = makeRenderer(canvas); if (!R) return
      R.resize(img.naturalWidth, img.naturalHeight); R.upload(img)
      R.draw('cur', img.naturalWidth, img.naturalHeight, { lo: 0, hi: 0, contrast: 0, sat: 1, gamma: 1 }, cfg.sharpness)
      canvas.toBlob(b => { R.dispose(); if (b) { img.dataset.minEnhanced = '1'; img.removeAttribute('srcset'); img.src = URL.createObjectURL(b) } }, 'image/webp', 0.92)
    } catch (e) { /* tainted cross-origin image */ }
  }

  /* ---------------- fullscreen button (like Samsung Internet) ---------------- */
  const fsBtn = document.createElement('div')
  fsBtn.setAttribute('data-aib-fs', '1')
  fsBtn.style.cssText = 'position:fixed;z-index:2147483647;width:44px;height:44px;border-radius:22px;background:rgba(20,20,20,.72);' +
    'color:#fff;font:22px/44px sans-serif;text-align:center;display:none;touch-action:manipulation;-webkit-tap-highlight-color:transparent;user-select:none'
  let fsTarget = null; let lastTouch = 0; let fsOk = false
  function playerBox (video) { // climb to the element that wraps the video + its controls
    const r = video.getBoundingClientRect(); let el = video
    for (let i = 0; i < 6 && el.parentElement && el.parentElement !== document.body; i++) {
      const pr = el.parentElement.getBoundingClientRect()
      if (pr.width > r.width * 1.15 + 4 || pr.height > r.height * 1.15 + 4) break
      el = el.parentElement
    }
    return el === video ? (video.parentElement || video) : el
  }
  function enterFs () {
    if (!fsTarget) return
    const cur = document.fullscreenElement || document.webkitFullscreenElement
    if (cur) { (document.exitFullscreen || document.webkitExitFullscreen).call(document); return }
    const box = playerBox(fsTarget); const req = box.requestFullscreen || box.webkitRequestFullscreen
    if (req) req.call(box).catch(() => { try { fsTarget.webkitEnterFullscreen && fsTarget.webkitEnterFullscreen() } catch (e) { log('fullscreen failed') } })
    else if (fsTarget.webkitEnterFullscreen) fsTarget.webkitEnterFullscreen()
  }
  fsBtn.addEventListener('click', (e) => { e.stopPropagation(); e.preventDefault(); enterFs() }, true)
  const bump = (e) => { lastTouch = performance.now(); if (e && e.target === fsBtn) return }
  ;['touchstart', 'pointerdown', 'mousemove', 'click'].forEach(ev => document.addEventListener(ev, bump, { capture: true, passive: true }))
  function updateFsButton () {
    let best = null; let area = 0
    document.querySelectorAll('video').forEach(v => {
      const r = v.getBoundingClientRect()
      if (r.width < 200 || r.height < 110 || r.bottom <= 0 || r.top >= window.innerHeight || v.readyState < 2 || v.ended) return
      if (r.width * r.height > area) { best = v; area = r.width * r.height }
    })
    const inFs = !!(document.fullscreenElement || document.webkitFullscreenElement)
    // do not offer the button for videos that already fill the whole screen unless we are in fullscreen
    if (!best || performance.now() - lastTouch > 3500 && !best.paused) { fsBtn.style.display = 'none'; return }
    fsTarget = best
    const r = best.getBoundingClientRect(); const top = Math.max(8, r.top + 8); const right = Math.min(window.innerWidth - 8, r.right - 8)
    const parent = document.fullscreenElement || document.webkitFullscreenElement || document.documentElement
    if (fsBtn.parentNode !== parent) parent.appendChild(fsBtn)
    fsBtn.textContent = inFs ? '✕' : '⛶'
    fsBtn.style.left = Math.max(8, right - 44) + 'px'; fsBtn.style.top = top + 'px'; fsBtn.style.display = 'block'
  }
  setInterval(updateFsButton, 400)

  /* ---------------- orchestration ---------------- */
  function scan () {
    if (!cfg.enabled || state.failed) return
    document.querySelectorAll('video').forEach(attach)
    if (cfg.images) document.querySelectorAll('img').forEach(upscaleImage)
  }
  let timer = null
  new window.MutationObserver(() => { clearTimeout(timer); timer = setTimeout(scan, 400) }).observe(document, { childList: true, subtree: true })
  document.addEventListener('visibilitychange', () => { if (!document.hidden) state.videos.forEach((_, v) => loop(v)) })
  const CFG_KEYS = ['enabled', 'target', 'sharpness', 'neural', 'denoise', 'tone', 'temporal', 'interp', 'interpNeural', 'images']
  function forward (c) { // push the config into every child frame (players live in iframes)
    for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage({ __aibCfg: c }, '*') } catch (e) { /* ignore */ } }
  }
  window.addEventListener('message', (e) => {
    if (e.source !== window.parent || !e.data || !e.data.__aibCfg) return
    const c = {}; for (const k of CFG_KEYS) if (k in e.data.__aibCfg) c[k] = e.data.__aibCfg[k]
    window.__minEnhanceSet(c)
  })
  window.__minEnhanceSet = function (c) {
    if (c && c.interp === true) guard.noInterp = false
    if (c && c.neural === true) guard.noNeural = false
    Object.assign(cfg, c || {}); forward(c || {})
    if (cfg.enabled) { scan(); state.videos.forEach((_, v) => loop(v)) } else Array.from(state.videos.keys()).forEach(detach)
  }
  window.__minEnhance = { cfg, state }
  log('init', window.top === window ? 'TOP' : 'FRAME', location.host, 'enabled=' + cfg.enabled, 'neural=' + cfg.neural, 'target=' + (cfg.target || 'auto'))
  if (window.top === window && navigator.gpu) navigator.gpu.requestAdapter().then(a => log('webgpu adapter:', a ? 'ok' : 'none')).catch(e => log('webgpu adapter error', String(e)))
  scan()
})()
