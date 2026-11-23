/* On-device video/image enhancement engine (runs inside the page, no network needed).
   Pipeline per video frame, all on the GPU (WebGL2):
     [optional] neural 4x super-resolution (Real-ESRGAN general-x4v3 via ONNX Runtime Web, WebGPU)
     Catmull-Rom resample -> denoise/deblock -> contrast-adaptive sharpen -> adaptive tone mapping.
   Native code sets configuration with window.__minEnhanceSet({...}). */
;(function () {
  if (window.__minEnhance) return
  const HOST = 'https://appassets.min.local/'
  const cfg = {
    enabled: true, target: 1440, sharpness: 0.6, neural: true, denoise: 0.35,
    tone: true, images: true, gestures: false, autoNative: true
  }
  Object.assign(cfg, window.__minCfg0 || {})
  /* Read-ahead for site players: hls.js and video.js normally fetch only ~30 s ahead; ask them to keep loading
     up to 10 minutes ahead (capped at ~200 MB), so a slow or dropping connection does not stall the movie. */
  ;(function bufferAhead () {
    const tuneHls = (H) => { try { const c = H && H.DefaultConfig; if (c && !c.__aib) { c.__aib = 1; c.maxBufferLength = 600; c.maxMaxBufferLength = 600; c.maxBufferSize = 200 * 1000 * 1000; c.backBufferLength = 120 } } catch (e) { /* ignore */ } }
    const tuneVjs = (V) => { try { const v = V && (V.Vhs || V.Hls); if (v && !v.__aib) { v.__aib = 1; v.GOAL_BUFFER_LENGTH = 600; v.MAX_GOAL_BUFFER_LENGTH = 600; v.GOAL_BUFFER_LENGTH_RATE = 1 } } catch (e) { /* ignore */ } }
    const trap = (name, tune) => {
      if (window[name]) { tune(window[name]); return }
      let val
      try { Object.defineProperty(window, name, { configurable: true, enumerable: true, get () { return val }, set (v) { val = v; tune(v) } }) } catch (e) { /* ignore */ }
    }
    trap('Hls', tuneHls); trap('videojs', tuneVjs)
    // players that load later through a bundler: check again once the page has loaded
    window.addEventListener('load', () => { tuneHls(window.Hls); tuneVjs(window.videojs) })
  })()
  const nativeShort = () => Math.round(Math.min(screen.width, screen.height) * (window.devicePixelRatio || 1))
  const effTarget = () => cfg.target || nativeShort()
  try { // WebView: screen.orientation.lock() rejects, which breaks many players' fullscreen; native code rotates instead
    if (screen.orientation) { screen.orientation.lock = () => Promise.resolve(); screen.orientation.unlock = () => {} }
  } catch (e) { /* ignore */ }
  // Performance guard: watches main-thread long tasks while a video plays and pauses neural upscaling. Each repeated overload pauses it longer (20s, 60s, 3min); the 4th time it stays
  // off until the page is reloaded, so the phone never stays overloaded.
  const guard = { noNeural: false, strikes: 0, retryAt: 0, off: false }
  const longTasks = []
  // live list: the periodic jobs below return at once in frames without any video (most ad and widget iframes)
  const videoEls = document.getElementsByTagName('video')
  try { new PerformanceObserver(l => { if (videoEls.length) for (const e of l.getEntries()) longTasks.push([e.startTime, e.duration]) }).observe({ entryTypes: ['longtask'] }) } catch (e) { /* unsupported */ }
  setInterval(() => {
    if (!videoEls.length && !state.videos.size) { longTasks.length = 0; return }
    const now = performance.now()
    while (longTasks.length && now - longTasks[0][0] > 3000) longTasks.shift()
    const busy = longTasks.reduce((a, t) => a + t[1], 0)
    const playing = Array.from(state.videos.keys()).some(v => !v.paused)
    if (guard.retryAt && now > guard.retryAt && !guard.off) { guard.noNeural = false; guard.retryAt = 0; longTasks.length = 0; log('performance guard: retrying heavy features') }
    if (!playing || busy < 450) return
    if (!cfg.neural || guard.noNeural) return
    guard.noNeural = true; guard.strikes++; longTasks.length = 0
    if (guard.strikes >= 4) { guard.off = true; log('performance guard: neural upscaling off for this page (busy ' + Math.round(busy) + 'ms/3s)'); return }
    const secs = [0, 20, 60, 180][guard.strikes]
    guard.retryAt = now + secs * 1000
    log('performance guard: neural upscaling paused ' + secs + 's (busy ' + Math.round(busy) + 'ms/3s)')
  }, 1500)
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

  const FS_PLANES = `#version 300 es
precision highp float; uniform sampler2D pr, pg, pb; in vec2 uv; out vec4 o;
void main(){ o=vec4(texture(pr,uv).r, texture(pg,uv).r, texture(pb,uv).r, 1.); }`

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

  /* Shader path (every frame when the neural model cannot keep up, and for images): AMD FidelityFX Super Resolution 1
     (MIT licence), ported to WebGL2. EASU is an edge-adaptive Lanczos-like upscale: it follows the direction of each edge
     instead of blurring across it like bicubic, so lines and text stay clean at 2-4x. RCAS then sharpens with a limit
     that never pushes a pixel past its neighbours (no halos, no ringing). A light deblock runs before, at source size. */
  const FS_DENOISE = `#version 300 es
precision highp float; uniform sampler2D tex; uniform vec2 texSize; uniform float denoise; in vec2 uv; out vec4 o;
void main(){
  vec2 px=1.0/texSize; vec3 c=texture(tex,uv).rgb; vec3 acc=c; float ws=1.0; float k=denoise*224.0;
  vec3 n[4]=vec3[4](texture(tex,uv+vec2(0.,-px.y)).rgb, texture(tex,uv+vec2(-px.x,0.)).rgb, texture(tex,uv+vec2(px.x,0.)).rgb, texture(tex,uv+vec2(0.,px.y)).rgb);
  for(int i=0;i<4;i++){ float w=exp(-k*dot(n[i]-c,n[i]-c)); acc+=n[i]*w; ws+=w; }
  o=vec4(mix(c,acc/ws,clamp(denoise*1.6,0.,1.)),1.); }`

  const FS_EASU = `#version 300 es
precision highp float; uniform sampler2D tex; uniform vec2 inSize, outSize; out vec4 o;
vec3 F(vec2 p){ return texelFetch(tex, ivec2(clamp(p, vec2(0.), inSize-1.)), 0).rgb; }
float L(vec3 c){ return c.b*0.5+(c.r*0.5+c.g); }
void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c){
  vec2 v=vec2(dot(off,dir), dot(off,vec2(-dir.y,dir.x)))*len;
  float d2=min(dot(v,v),clp); float wB=0.4*d2-1.0; float wA=lob*d2-1.0;
  wB*=wB; wA*=wA; wB=1.5625*wB-0.5625; float w=wB*wA; aC+=c*w; aW+=w; }
void dirLen(inout vec2 dir, inout float len, float w, float a, float b, float c, float d, float e){
  float lx=max(abs(d-c),abs(c-b)); float dx=d-b; dir.x+=dx*w; lx=clamp(abs(dx)/max(lx,1e-5),0.,1.); len+=lx*lx*w;
  float ly=max(abs(e-c),abs(c-a)); float dy=e-a; dir.y+=dy*w; ly=clamp(abs(dy)/max(ly,1e-5),0.,1.); len+=ly*ly*w; }
void main(){
  vec2 pp=gl_FragCoord.xy*inSize/outSize-0.5; vec2 fp=floor(pp); pp-=fp;
  vec3 b=F(fp+vec2(0,-1)), c=F(fp+vec2(1,-1)), e=F(fp+vec2(-1,0)), f=F(fp), g=F(fp+vec2(1,0)), h=F(fp+vec2(2,0)),
       i=F(fp+vec2(-1,1)), j=F(fp+vec2(0,1)), k=F(fp+vec2(1,1)), l=F(fp+vec2(2,1)), n=F(fp+vec2(0,2)), oo=F(fp+vec2(1,2));
  float bL=L(b),cL=L(c),eL=L(e),fL=L(f),gL=L(g),hL=L(h),iL=L(i),jL=L(j),kL=L(k),lL=L(l),nL=L(n),oL=L(oo);
  vec2 dir=vec2(0.); float len=0.;
  dirLen(dir,len,(1.-pp.x)*(1.-pp.y),bL,eL,fL,gL,jL); dirLen(dir,len,pp.x*(1.-pp.y),cL,fL,gL,hL,kL);
  dirLen(dir,len,(1.-pp.x)*pp.y,fL,iL,jL,kL,nL); dirLen(dir,len,pp.x*pp.y,gL,jL,kL,lL,oL);
  float r=dot(dir,dir); bool z=r<1.0/32768.0; dir=z?vec2(1.,0.):dir*inversesqrt(r);
  len=len*0.5; len*=len;
  float st=dot(dir,dir)/max(abs(dir.x),abs(dir.y)); vec2 len2=vec2(1.+(st-1.)*len, 1.-0.5*len);
  float lob=0.5-0.29*len; float clp=1.0/lob;
  vec3 aC=vec3(0.); float aW=0.;
  tap(aC,aW,vec2(0,-1)-pp,dir,len2,lob,clp,b); tap(aC,aW,vec2(1,-1)-pp,dir,len2,lob,clp,c);
  tap(aC,aW,vec2(-1,1)-pp,dir,len2,lob,clp,i); tap(aC,aW,vec2(0,1)-pp,dir,len2,lob,clp,j);
  tap(aC,aW,vec2(0,0)-pp,dir,len2,lob,clp,f); tap(aC,aW,vec2(-1,0)-pp,dir,len2,lob,clp,e);
  tap(aC,aW,vec2(1,1)-pp,dir,len2,lob,clp,k); tap(aC,aW,vec2(2,1)-pp,dir,len2,lob,clp,l);
  tap(aC,aW,vec2(2,0)-pp,dir,len2,lob,clp,h); tap(aC,aW,vec2(1,0)-pp,dir,len2,lob,clp,g);
  tap(aC,aW,vec2(1,2)-pp,dir,len2,lob,clp,oo); tap(aC,aW,vec2(0,2)-pp,dir,len2,lob,clp,n);
  vec3 mn=min(min(f,g),min(j,k)), mx=max(max(f,g),max(j,k));
  o=vec4(clamp(aC/aW,mn,mx),1.); }`

  // RCAS + the same adaptive tone mapping as FS_MAIN; drawn to the screen at the canvas size
  const FS_RCAS = `#version 300 es
precision highp float; uniform sampler2D tex; uniform vec2 outSize; uniform float sharp;
uniform vec4 tone; uniform float gammaV; in vec2 uv; out vec4 o;
vec3 F(ivec2 p){ return texelFetch(tex, clamp(p, ivec2(0), ivec2(outSize)-1), 0).rgb; }
void main(){
  ivec2 ip=ivec2(uv*outSize);
  vec3 b=F(ip+ivec2(0,-1)), d=F(ip+ivec2(-1,0)), e=F(ip), f=F(ip+ivec2(1,0)), h=F(ip+ivec2(0,1));
  vec3 mn=min(min(b,d),min(f,h)), mx=max(max(b,d),max(f,h));
  vec3 hitMin=mn/max(4.0*mx,1e-4); vec3 hitMax=(1.0-mx)/min(4.0*mn-4.0,-1e-4);
  vec3 lo=max(-hitMin,hitMax); float lobe=max(-0.1875,min(max(lo.r,max(lo.g,lo.b)),0.))*sharp;
  vec3 r=clamp((lobe*(b+d+f+h)+e)/(4.0*lobe+1.0),0.,1.);
  if(tone.y>tone.x){
    r=clamp((r-tone.x)/(tone.y-tone.x),0.,1.); r=pow(r,vec3(gammaV));
    r=mix(r, r*r*(3.0-2.0*r), tone.z);
    float l=dot(r,vec3(.2126,.7152,.0722)); r=mix(vec3(l),r,tone.w); }
  o=vec4(r,1.); }`

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
    R.planesP = program(gl, FS_PLANES, VS_FBO); R.main = program(gl, FS_MAIN)
    R.dn = program(gl, FS_DENOISE, VS_FBO); R.easu = program(gl, FS_EASU, VS_FBO); R.rcas = program(gl, FS_RCAS)
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0)
    R.texCur = texture(gl); R.texNN = texture(gl); R.texDn = texture(gl, 0, 0, gl.NEAREST); R.texUp = texture(gl, 0, 0, gl.NEAREST)
    R.fbo = gl.createFramebuffer()
    R.resize = function (w, h) { R.w = w; R.h = h }
    R.bind = function (unit, tex) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex) }
    R.pass = function (prog, target, w, h, setup) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, target ? R.fbo : null)
      if (target) gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0)
      gl.viewport(0, 0, w, h); gl.useProgram(prog.p); setup(prog.u); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
    }
    R.upload = function (source) { // upload the new frame (throws on tainted media)
      R.bind(0, R.texCur)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
    }
    R.uploadPlanes = function (data, w, h) { // Float32 NCHW (3 planes) straight to the GPU; converted by a shader
      const n = w * h
      if (!R.texP) R.texP = [0, 1, 2].map(() => texture(gl, 0, 0, gl.NEAREST))
      if (R.pw !== w || R.ph !== h) {
        R.pw = w; R.ph = h
        R.bind(0, R.texNN); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
      }
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
      for (let i = 0; i < 3; i++) { R.bind(i, R.texP[i]); gl.texImage2D(gl.TEXTURE_2D, 0, gl.R32F, w, h, 0, gl.RED, gl.FLOAT, data.subarray(i * n, (i + 1) * n)) }
      R.pass(R.planesP, R.texNN, w, h, u => { gl.uniform1i(u.pr, 0); gl.uniform1i(u.pg, 1); gl.uniform1i(u.pb, 2) })
      // the float planes are only needed for this conversion: shrink them (3 x 50 MB of GPU memory at 660p)
      for (let i = 0; i < 3; i++) { R.bind(i, R.texP[i]); gl.texImage2D(gl.TEXTURE_2D, 0, gl.R32F, 1, 1, 0, gl.RED, gl.FLOAT, null) }
    }
    // which: 'cur' (current video frame) | 'nn' (neural 4x output). Only the current frame is ever shown:
    // nothing is blended with earlier frames, so motion can never leave a trail.
    R.draw = function (which, vw, vh, tone, sharp) {
      if (which === 'cur') return R.drawFsr(vw, vh, tone, sharp)
      let src = R.texCur; let sw = vw; let sh = vh
      if (which === 'nn') { src = R.texNN; sw = vw * 4; sh = vh * 4 }
      R.pass(R.main, null, R.canvas.width, R.canvas.height, u => {
        R.bind(0, src); gl.uniform1i(u.tex, 0); gl.uniform2f(u.texSize, sw, sh)
        gl.uniform1f(u.sharp, sharp); gl.uniform1f(u.denoise, which === 'nn' ? cfg.denoise * 0.3 : cfg.denoise)
        gl.uniform4f(u.tone, tone.lo, tone.hi, tone.contrast, tone.sat); gl.uniform1f(u.gammaV, tone.gamma)
      })
    }
    // current frame: deblock (source size) -> EASU (to canvas size) -> RCAS + tone (screen)
    R.drawFsr = function (vw, vh, tone, sharp) {
      const ow = R.canvas.width; const oh = R.canvas.height
      let src = R.texCur
      if (cfg.denoise > 0) {
        if (R.dw !== vw || R.dh !== vh) { R.dw = vw; R.dh = vh; R.bind(0, R.texDn); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, vw, vh, 0, gl.RGBA, gl.UNSIGNED_BYTE, null) }
        R.pass(R.dn, R.texDn, vw, vh, u => { R.bind(0, R.texCur); gl.uniform1i(u.tex, 0); gl.uniform2f(u.texSize, vw, vh); gl.uniform1f(u.denoise, cfg.denoise) })
        src = R.texDn
      }
      if (R.uw !== ow || R.uh !== oh) { R.uw = ow; R.uh = oh; R.bind(0, R.texUp); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, ow, oh, 0, gl.RGBA, gl.UNSIGNED_BYTE, null) }
      R.pass(R.easu, R.texUp, ow, oh, u => { R.bind(0, src); gl.uniform1i(u.tex, 0); gl.uniform2f(u.inSize, vw, vh); gl.uniform2f(u.outSize, ow, oh) })
      // cfg.sharpness 0..1 -> RCAS strength 2^-stops (0 stops = strongest)
      R.pass(R.rcas, null, ow, oh, u => {
        R.bind(0, R.texUp); gl.uniform1i(u.tex, 0); gl.uniform2f(u.outSize, ow, oh); gl.uniform1f(u.sharp, Math.pow(2, -(1 - Math.min(1, Math.max(0, sharp)))))
        gl.uniform4f(u.tone, tone.lo, tone.hi, tone.contrast, tone.sat); gl.uniform1f(u.gammaV, tone.gamma)
      })
    }
    R.dispose = function () { const e = gl.getExtension('WEBGL_lose_context'); if (e) e.loseContext() }
    gl.getExtension('EXT_color_buffer_float')
    return R
  }

  /* ---------------- adaptive tone analysis ---------------- */
  const toneCanvas = document.createElement('canvas'); toneCanvas.width = 32; toneCanvas.height = 18
  const toneCtx = toneCanvas.getContext('2d', { willReadFrequently: true })
  /**
   * Dynamic per-scene tone (the idea behind HDR10+): black/white points and gamma follow every scene.
   * A scene cut is detected from the brightness histogram and the new values apply at once (no slow fade);
   * inside a scene they follow quickly (~150 ms) but ignore tiny changes, so the picture does not pump or flicker.
   */
  function analyseTone (st, video) {
    toneCtx.drawImage(video, 0, 0, 32, 18)
    const d = toneCtx.getImageData(0, 0, 32, 18).data; const ls = []; const hist = new Float32Array(8)
    for (let i = 0; i < d.length; i += 4) { const l = (0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]) / 255; ls.push(l); hist[Math.min(7, l * 8 | 0)]++ }
    ls.sort((a, b) => a - b)
    const n = ls.length; for (let i = 0; i < 8; i++) hist[i] /= n
    const lo = ls[Math.floor(n * 0.02)]; const hi = ls[Math.floor(n * 0.98)]
    const mean = ls.reduce((a, b) => a + b, 0) / n
    const gamma = Math.min(1.25, Math.max(0.8, Math.log(0.45) / Math.log(Math.min(0.9, Math.max(0.1, (mean - lo) / Math.max(hi - lo, 0.05))))))
    const t = st.tone
    let diff = 0; if (st.toneHist) for (let i = 0; i < 8; i++) diff += Math.abs(hist[i] - st.toneHist[i])
    st.toneHist = hist
    const now = performance.now(); const dt = Math.min(500, now - (st.toneT || now)); st.toneT = now
    const cut = !st.toneHist0 || diff > 0.45; st.toneHist0 = true
    const k = cut ? 1 : 1 - Math.exp(-dt / 150)
    const tlo = lo * 0.9; const thi = Math.max(hi, lo + 0.2)
    const dead = cut ? 0 : 0.008 // ignore noise-level changes inside a scene
    if (Math.abs(tlo - t.lo) > dead) t.lo += (tlo - t.lo) * k
    if (Math.abs(thi - t.hi) > dead) t.hi += (thi - t.hi) * k
    if (Math.abs(gamma - t.gamma) > dead) t.gamma += (gamma - t.gamma) * k
  }

  /* ---------------- neural 4x via ONNX Runtime Web (in page) ---------------- */
  function nnInit () {
    if (state.nn || !window.ort) { if (!window.ort) state.stats.nn = 'ort-missing'; return }
    const nn = state.nn = { session: null, busy: false, last: null, ms: 0, id: 0, rid: 0 }
    state.stats.nn = 'loading'
    const ort = window.ort
    ort.env.wasm.wasmPaths = HOST + 'ort/'; ort.env.wasm.numThreads = 1
    ;(async () => {
      // WebGPU only. On the CPU (wasm) one 360p frame takes many seconds and blocks the page's main thread: the player
      // and scrolling freeze. Without WebGPU (older Android WebView, some GPUs) the shader path (FSR) is used instead.
      let hasGpu = false
      try { hasGpu = !!(navigator.gpu && await navigator.gpu.requestAdapter()) } catch (e) { /* none */ }
      if (!hasGpu && !cfg.nnCpu) { state.stats.nn = 'error'; log('neural SR: no WebGPU in this WebView, shader upscaling (FSR) only'); return }
      const bytes = new Uint8Array(await (await fetch(HOST + 'model/realesr-general-x4v3.onnx')).arrayBuffer())
      for (const ep of hasGpu ? ['webgpu'] : ['wasm']) { // wasm only for the engine tests (cfg.nnCpu, never set by the app)
        try { nn.session = await ort.InferenceSession.create(bytes, { executionProviders: [ep], graphOptimizationLevel: 'all' }); nn.backend = ep; break } catch (e) { log('SR backend', ep, 'failed:', String(e)) }
      }
      state.stats.nn = nn.session ? 'ready:' + nn.backend : 'error'
      log('neural SR model', state.stats.nn)
    })().catch((e) => { state.stats.nn = 'error'; log('neural SR load failed:', String(e)) })
  }
  const grab = document.createElement('canvas'); const grabCtx = grab.getContext('2d', { willReadFrequently: true })
  async function nnRun (video, vw, vh) { // the result's Float32 planes are dropped once uploaded to the GPU
    const nn = state.nn; nn.busy = true
    try {
      grab.width = vw; grab.height = vh; grabCtx.drawImage(video, 0, 0, vw, vh)
      const d = grabCtx.getImageData(0, 0, vw, vh).data; const px = vw * vh; const inp = new Float32Array(3 * px)
      for (let i = 0, j = 0; i < px; i++, j += 4) { inp[i] = d[j] / 255; inp[px + i] = d[j + 1] / 255; inp[2 * px + i] = d[j + 2] / 255 }
      const mt = video.currentTime; const t0 = performance.now()
      const out = await nn.session.run({ [nn.session.inputNames[0]]: new window.ort.Tensor('float32', inp, [1, 3, vh, vw]) })
      nn.ms = nn.ms ? nn.ms * 0.7 + (performance.now() - t0) * 0.3 : performance.now() - t0
      const o = out[nn.session.outputNames[0]].data; const ow = vw * 4; const oh = vh * 4 // Float32 NCHW, converted on the GPU
      nn.last = state.videos.has(video) ? { video, mediaTime: mt, w: ow, h: oh, planes: o, id: ++nn.rid } : null
    } catch (e) { state.stats.nn = 'error'; log('SR inference failed:', String(e)) } finally { nn.busy = false }
  }

  /* ---------------- video ---------------- */
  function outSize (vw, vh, rect) {
    const dpr = window.devicePixelRatio || 1
    let h = Math.min(effTarget(), Math.round(rect.height * dpr)); let w = Math.round(h * rect.width / Math.max(rect.height, 1))
    if (h <= vh) { h = vh; w = vw }
    return [Math.max(w, 2), Math.max(h, 2)]
  }
  // The picture inside a <video> keeps its aspect ratio (object-fit: contain by default), so the
  // overlay must cover only the real picture, never the whole element (that stretched the video).
  function fitRect (video, rect, vw, vh) {
    const fit = (getComputedStyle(video).objectFit || 'contain')
    if (fit === 'fill' || !vw || !vh) return { left: rect.left, top: rect.top, width: rect.width, height: rect.height, clip: '' }
    const ar = vw / vh; const er = rect.width / Math.max(rect.height, 1)
    let w, h
    if (fit === 'cover' ? ar < er : ar > er) { w = rect.width; h = w / ar } else { h = rect.height; w = h * ar }
    if (fit === 'none' || fit === 'scale-down') { const sw = Math.min(w, vw); const sh = sw / ar; if (fit === 'none' || sw < w) { w = vw; h = vh } else { w = sw; h = sh } }
    const left = rect.left + (rect.width - w) / 2; const top = rect.top + (rect.height - h) / 2
    const clip = fit === 'cover' ? `inset(${Math.max(0, rect.top - top)}px ${Math.max(0, left + w - rect.right)}px ${Math.max(0, top + h - rect.bottom)}px ${Math.max(0, rect.left - left)}px)` : ''
    return { left, top, width: w, height: h, clip }
  }
  function host () { return document.fullscreenElement || document.webkitFullscreenElement || document.documentElement }
  document.addEventListener('fullscreenchange', () => state.videos.forEach(st => host().appendChild(st.canvas)))
  document.addEventListener('webkitfullscreenchange', () => state.videos.forEach(st => host().appendChild(st.canvas)))
  function attach (video) {
    if (video.mediaKeys) { log('DRM video skipped'); return }
    if (state.videos.has(video) || state.failed || lostVideos.has(video)) return
    const canvas = document.createElement('canvas')
    canvas.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;display:none'
    let R
    try { R = makeRenderer(canvas) } catch (e) { R = null; console.warn('enhance: shader error', e) }
    if (!R) { state.failed = true; log('WebGL2 unavailable'); return }
    const st = { canvas, R, running: false, prevOpacity: video.style.opacity, errors: 0, frame: 0, tone: { lo: 0, hi: 1, contrast: 0.22, sat: 1.1, gamma: 1 }, lastT: 0, fpsEMA: 30 }
    state.videos.set(video, st); host().appendChild(canvas)
    // the browser may drop the context (GPU reset, too many contexts): show the real video again, never a dead canvas
    canvas.addEventListener('webglcontextlost', () => { if (state.videos.get(video) === st) lost(video) })
    video.addEventListener('play', () => { st.fpsEMA = 30; st.lastT = 0; loop(video) })
    // a frame callback pending at pause never fires (no new frame): switch to the paused timer, so the paused
    // frame still gets its neural pass
    video.addEventListener('pause', () => { if (st.vfc != null && state.videos.get(video) === st) { video.cancelVideoFrameCallback(st.vfc); st.vfc = null; st.resume() } })
    loop(video)
  }
  function detach (video) {
    const st = state.videos.get(video); if (!st) return
    state.videos.delete(video); st.canvas.remove(); st.R.dispose(); video.style.opacity = st.prevOpacity
    if (state.nn && state.nn.last && state.nn.last.video === video) state.nn.last = null
  }
  const lostVideos = new WeakSet() // their WebGL context was lost: left to the browser for the rest of the page
  function lost (video) { log('video renderer context lost, showing the original video'); lostVideos.add(video); detach(video) }
  function loop (video) {
    const st = state.videos.get(video); if (!st || st.running) return
    st.running = true
    const next = (cb) => {
      if (video.requestVideoFrameCallback && !video.paused) st.vfc = video.requestVideoFrameCallback((n, m) => { st.vfc = null; cb(n, m) })
      else setTimeout(() => requestAnimationFrame(cb), video.paused ? 250 : 16)
    }
    const tick = (now, meta) => {
      if (video.mediaKeys) { detach(video); st.running = false; return }
      if (!state.videos.has(video) || !cfg.enabled) { st.running = false; return }
      if (st.R.gl.isContextLost()) { lost(video); st.running = false; return }
      const rect = video.getBoundingClientRect(); const vw = video.videoWidth; const vh = video.videoHeight; const dpr = window.devicePixelRatio || 1
      const wantH = Math.min(effTarget(), fitRect(video, rect, vw, vh).height * dpr)
      const visible = vw > 0 && video.readyState >= 2 && rect.width > 40 && rect.height > 40 && rect.bottom > 0 && rect.top < window.innerHeight && !document.hidden
      const why = !visible ? 'hidden/not-ready' : (vh >= wantH * 0.95 ? 'source already >= target' : 'enhancing')
      if (st.why !== why) { st.why = why; log('video', vw + 'x' + vh, 'shown=' + Math.round(rect.width) + 'x' + Math.round(rect.height) + 'css', 'wantH=' + Math.round(wantH), '=>', why, 'frame=' + (window.top === window ? 'TOP' : 'FRAME')) }
      if (!(visible && vh < wantH * 0.95)) { st.canvas.style.display = 'none'; video.style.opacity = st.prevOpacity; next(tick); return }
      const fr = fitRect(video, rect, vw, vh)
      const [w, h] = outSize(vw, vh, fr)
      if (st.canvas.width !== w || st.canvas.height !== h) { st.canvas.width = w; st.canvas.height = h }
      const s = st.canvas.style; s.display = 'block'; s.left = fr.left + 'px'; s.top = fr.top + 'px'; s.width = fr.width + 'px'; s.height = fr.height + 'px'; s.clipPath = fr.clip
      state.stats.fps = Math.round(st.fpsEMA)
      state.stats.active = Array.from(state.videos.values()).filter(x => x.canvas.style.display === 'block').length
      try {
        st.R.resize(vw, vh); st.R.upload(video); st.frame++
        if (cfg.tone && st.frame % 3 === 1) analyseTone(st, video) // every 3rd frame (each read copies the video frame to the CPU): scene cuts still snap within ~0.1 s
        const tone = cfg.tone ? st.tone : { lo: 0, hi: 0, contrast: 0, sat: 1, gamma: 1 }
        const dt = st.lastT ? (now - st.lastT) : 33; st.lastT = now; st.fpsEMA = st.fpsEMA * 0.9 + (1000 / Math.max(dt, 1)) * 0.1
        const nnLiveOk = vh <= 660 // 360p..660p get the neural 4x path at their own size (the result is scaled to the screen); bigger sources use the shader path
        const wantNN = cfg.neural && (video.paused || (!guard.noNeural && nnLiveOk)) && vh <= 660 && state.nn && state.nn.session && state.stats.nn !== 'error'
        if (cfg.neural && vh <= 660) nnInit()
        // Neural frames are only shown when they are consistent: exact frame while paused, or when the
        // model keeps up with the video (always one steady frame late). Never mix stale and live frames.
        const nnS = state.nn; const T = 1000 / Math.max(st.fpsEMA, 1)
        const fast = !!nnS && nnS.ms > 0 && nnS.ms < 0.6 * T
        // while paused the model runs once per shown frame (a seek or a new size gives a new frame), not over and over
        const frameKey = video.paused ? video.currentTime + ':' + vw + 'x' + vh : ''
        if (!video.paused) st.nnKey = ''
        if (wantNN && !nnS.busy && (video.paused ? st.nnKey !== frameKey && !video.seeking : fast || !nnS.ms)) { nnS.lastProbe = performance.now(); st.nnKey = frameKey; nnRun(video, vw, vh) }
        const r = nnS && nnS.last
        const fresh = wantNN && r && r.video === video && r.w === vw * 4 && (r.planes || st.nnId === r.id) &&
          (video.paused ? Math.abs(r.mediaTime - video.currentTime) < 0.05 : fast && Math.abs(r.mediaTime - video.currentTime) < 3 * T / 1000)
        if (fresh) {
          if (st.nnId !== r.id) { st.R.uploadPlanes(r.planes, r.w, r.h); st.nnId = r.id; r.planes = null } // now on the GPU: free the Float32 copy
          st.R.draw('nn', vw, vh, tone, cfg.sharpness * 0.3)
        } else {
          // a playing video never goes back to a stale result: free it (~150 MB of Float32 at 660p)
          if (r && r.video === video && !video.paused) nnS.last = null
          st.R.draw('cur', vw, vh, tone, cfg.sharpness)
        }
        video.style.opacity = '0'; st.errors = 0
      } catch (e) { if (++st.errors > 3) { log('video disabled (', String(e), ') src=', (video.currentSrc || '').slice(0, 80)); detach(video); st.running = false; return } }
      next(tick)
    }
    st.resume = () => next(tick)
    tick(performance.now())
  }

  /* ---------------- images ---------------- */
  // All images share ONE WebGL renderer: a context per image used up the browser's limit (16), and then the
  // oldest context, the video's, was dropped.
  const doneImages = new WeakMap() // img -> the source it was processed for
  const imgKey = (img) => img.currentSrc + '\n' + img.src + '\n' + (img.getAttribute('srcset') || '')
  let imgR = null
  function imageRenderer () {
    if (imgR && !imgR.gl.isContextLost()) return imgR
    try { imgR = makeRenderer(document.createElement('canvas')) } catch (e) { imgR = null }
    return imgR
  }
  function readable (img) { // pixels of a cross-origin image without CORS cannot be read (WebGL throws)
    if (img.crossOrigin != null) return true // CORS request: it only loads if the server allowed it
    try { const u = new URL(img.currentSrc || img.src, location.href); return u.protocol === 'data:' || u.protocol === 'blob:' || u.origin === location.origin } catch (e) { return false }
  }
  function upscaleImage (img) { // returns true when the image was upscaled
    const key = imgKey(img)
    if (doneImages.get(img) === key || !img.complete || !img.naturalWidth || img.dataset.minEnhanced) return false
    const rect = img.getBoundingClientRect(); const dpr = window.devicePixelRatio || 1
    const wantW = Math.min(rect.width * dpr, effTarget() * (img.naturalWidth / img.naturalHeight))
    if (rect.width < 80 || img.naturalWidth >= wantW * 0.9 || img.naturalWidth * img.naturalHeight > 4e6) return false
    doneImages.set(img, key)
    if (!readable(img)) return false
    const R = imageRenderer(); if (!R) return false
    const scale = Math.min(4, Math.ceil(wantW / img.naturalWidth)); const w = img.naturalWidth * scale; const h = img.naturalHeight * scale
    const out = document.createElement('canvas'); out.width = w; out.height = h
    try {
      R.canvas.width = w; R.canvas.height = h
      R.resize(img.naturalWidth, img.naturalHeight); R.upload(img)
      R.draw('cur', img.naturalWidth, img.naturalHeight, { lo: 0, hi: 0, contrast: 0, sat: 1, gamma: 1 }, cfg.sharpness)
      out.getContext('2d').drawImage(R.canvas, 0, 0) // copied now: the shared canvas is reused for the next image
    } catch (e) { return false /* tainted (e.g. redirected cross-origin) image */ } finally { R.canvas.width = R.canvas.height = 1 }
    // a lazy loader may swap the picture while it is encoded: only replace the source that was upscaled
    out.toBlob(b => { if (b && imgKey(img) === key && !img.dataset.minEnhanced) { img.dataset.minEnhanced = '1'; img.removeAttribute('srcset'); img.src = URL.createObjectURL(b) } }, 'image/webp', 0.92)
    return true
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
  /* ---------------- player controls: speed, PiP, lock, double-tap seek, swipe brightness/volume ---------------- */
  const NATIVE = window.AIBNative
  const ctl = document.createElement('div') // toolbar that holds the fullscreen button and the extra controls
  ctl.setAttribute('data-aib-ctl', '1')
  ctl.style.cssText = 'position:fixed;z-index:2147483647;display:none;gap:8px;flex-direction:row-reverse'
  fsBtn.style.position = 'static'
  const mkBtn = (txt, title, fn) => {
    const b = document.createElement('div'); b.textContent = txt; b.title = title
    b.style.cssText = 'min-width:44px;height:44px;padding:0 8px;box-sizing:border-box;border-radius:22px;background:rgba(20,20,20,.72);color:#fff;font:600 16px/44px sans-serif;text-align:center;user-select:none;-webkit-tap-highlight-color:transparent'
    b.addEventListener('click', (e) => { e.stopPropagation(); e.preventDefault(); lastTouch = performance.now(); fn(b) }, true)
    return b
  }
  const SPEEDS = [1, 1.25, 1.5, 2, 0.75]
  const speedBtn = mkBtn('1×', 'speed', (b) => { if (!fsTarget) return; const i = (SPEEDS.indexOf(fsTarget.playbackRate) + 1) % SPEEDS.length; fsTarget.playbackRate = SPEEDS[i]; b.textContent = SPEEDS[i] + '×'; toast(fsTarget, '⏱ ' + SPEEDS[i] + '×') })
  const pipBtn = mkBtn('⧉', 'picture in picture', () => { if (NATIVE && NATIVE.pip) NATIVE.pip(); else if (fsTarget && fsTarget.requestPictureInPicture) fsTarget.requestPictureInPicture().catch(() => {}) })
  const lockBtn = mkBtn('🔒', 'lock', () => lockScreen(true))
  // native player: hardware decoding + neural upscaling on the phone's NPU
  const nativeBtn = mkBtn('AI ▶', 'native AI player', () => { if (!fsTarget || !NATIVE || !NATIVE.openNative) return; const v = fsTarget; NATIVE.openNative(v.currentSrc || v.src || '', v.currentTime || 0); try { v.pause() } catch (e) { /* ignore */ } })
  nativeBtn.style.background = 'rgba(124,92,255,.85)'
  ctl.append(fsBtn, pipBtn, speedBtn, lockBtn, nativeBtn)

  const bubble = document.createElement('div')
  bubble.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;padding:10px 18px;border-radius:24px;background:rgba(0,0,0,.65);color:#fff;font:600 18px sans-serif;display:none;transform:translate(-50%,-50%)'
  let bubbleTimer = 0
  function toast (v, text, xRel) {
    const r = v.getBoundingClientRect(); const parent = document.fullscreenElement || document.webkitFullscreenElement || document.documentElement
    if (bubble.parentNode !== parent) parent.appendChild(bubble)
    bubble.textContent = text; bubble.style.left = (r.left + r.width * (xRel == null ? 0.5 : xRel)) + 'px'; bubble.style.top = (r.top + r.height / 2) + 'px'; bubble.style.display = 'block'
    clearTimeout(bubbleTimer); bubbleTimer = setTimeout(() => { bubble.style.display = 'none' }, 700)
  }

  // lock: a transparent shield over the whole screen; tap it to show the unlock button
  const shield = document.createElement('div')
  shield.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:none;background:transparent;touch-action:none'
  const unlockBtn = mkBtn('🔓', 'unlock', () => lockScreen(false)); unlockBtn.style.cssText += ';position:absolute;left:50%;bottom:48px;transform:translateX(-50%);display:none'
  shield.appendChild(unlockBtn)
  let unlockTimer = 0
  const shieldTap = (e) => { if (e.target === unlockBtn) return; e.stopPropagation(); e.preventDefault(); unlockBtn.style.display = 'block'; clearTimeout(unlockTimer); unlockTimer = setTimeout(() => { unlockBtn.style.display = 'none' }, 2500) }
  ;['touchstart', 'touchend', 'click', 'pointerdown', 'pointerup', 'mousedown', 'mouseup'].forEach(ev => shield.addEventListener(ev, shieldTap, { capture: false, passive: false }))
  let locked = false
  function lockScreen (on) {
    locked = on
    if (on) { (document.fullscreenElement || document.webkitFullscreenElement || document.documentElement).appendChild(shield); shield.style.display = 'block'; unlockBtn.style.display = 'none'; ctl.style.display = 'none' } else { shield.style.display = 'none'; lastTouch = performance.now() }
  }
  document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && locked) lockScreen(false) })

  function videoAt (x, y) {
    let best = null; let area = 0
    document.querySelectorAll('video').forEach(v => {
      const r = v.getBoundingClientRect()
      if (r.width < 160 || r.height < 90 || x < r.left || x > r.right || y < r.top || y > r.bottom || v.readyState < 1) return
      if (r.width * r.height > area) { best = v; area = r.width * r.height }
    })
    return best
  }
  // double tap: left third -10s, right third +10s (repeated taps add up)
  let lastTap = { t: 0, x: 0, y: 0 }; let seekAcc = 0; let seekTimer = 0
  const swipe = { on: false, x0: 0, y0: 0, kind: '', v: null, acc: 0, sent: 0 }
  document.addEventListener('touchend', (e) => {
    if (!cfg.gestures || locked || e.changedTouches.length !== 1) return
    if (swipe.on) { swipe.on = false; return }
    const t = e.changedTouches[0]; const v = videoAt(t.clientX, t.clientY)
    if (!v || ctl.contains(e.target)) return
    const now = performance.now()
    if (now - lastTap.t < 330 && Math.abs(t.clientX - lastTap.x) < 70 && Math.abs(t.clientY - lastTap.y) < 70) {
      const r = v.getBoundingClientRect(); const rel = (t.clientX - r.left) / r.width
      if (rel < 0.38 || rel > 0.62) {
        const d = rel < 0.38 ? -10 : 10
        seekAcc = Math.sign(seekAcc) === Math.sign(d) ? seekAcc + d : d
        try { v.currentTime = Math.max(0, Math.min((v.duration || 1e9) - 0.5, v.currentTime + d)) } catch (err) { /* live stream */ }
        toast(v, (d < 0 ? '⏪ ' : '⏩ ') + Math.abs(seekAcc) + 'ث', rel < 0.38 ? 0.2 : 0.8)
        clearTimeout(seekTimer); seekTimer = setTimeout(() => { seekAcc = 0 }, 900)
        e.preventDefault(); e.stopPropagation(); lastTap.t = now // keep the chain going for +20, +30...
        return
      }
    }
    lastTap = { t: now, x: t.clientX, y: t.clientY }
  }, { capture: true, passive: false })
  // vertical swipe in fullscreen: left half brightness, right half volume
  const isFullVideo = (v) => { const r = v.getBoundingClientRect(); return !!(document.fullscreenElement || document.webkitFullscreenElement) || (r.width >= window.innerWidth * 0.95 && r.height >= window.innerHeight * 0.8) }
  document.addEventListener('touchstart', (e) => {
    swipe.on = false; swipe.kind = ''
    if (!cfg.gestures || locked || e.touches.length !== 1 || !NATIVE) return
    const t = e.touches[0]; const v = videoAt(t.clientX, t.clientY)
    if (!v || !isFullVideo(v) || ctl.contains(e.target)) { swipe.v = null; return }
    Object.assign(swipe, { x0: t.clientX, y0: t.clientY, v, acc: 0, sent: 0 })
  }, { capture: true, passive: true })
  document.addEventListener('touchmove', (e) => {
    if (!swipe.v || e.touches.length !== 1) return
    const t = e.touches[0]; const dx = t.clientX - swipe.x0; const dy = t.clientY - swipe.y0
    if (!swipe.on) {
      if (Math.abs(dy) < 22 || Math.abs(dy) < 2 * Math.abs(dx)) return
      const r = swipe.v.getBoundingClientRect(); swipe.on = true; swipe.kind = swipe.x0 < r.left + r.width / 2 ? 'b' : 'v'; swipe.y0 = t.clientY
    }
    e.preventDefault(); e.stopPropagation()
    const r = swipe.v.getBoundingClientRect(); const total = -(t.clientY - swipe.y0) / (r.height * 0.8)
    const step = total - swipe.sent; if (Math.abs(step) < 0.03) return
    swipe.sent = total
    try {
      if (swipe.kind === 'b') toast(swipe.v, '☀ ' + Math.round(NATIVE.brightness(step) * 100 || 0) + '%', 0.25)
      else toast(swipe.v, '🔊 ' + Math.round(NATIVE.volume(step) * 100) + '%', 0.75)
    } catch (err) { /* bridge missing */ }
  }, { capture: true, passive: false })

  // picture-in-picture: the playing video (and the iframes around it) temporarily fill the page
  const pipSaved = []
  function pipOn (el) { pipSaved.push([el, el.style.cssText]); el.style.cssText += ';position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483645!important;background:#000!important;object-fit:contain!important;transform:none!important;margin:0!important' }
  window.__aibPip = function (on) {
    for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage({ __aibPip: on }, '*') } catch (e) { /* ignore */ } }
    ctl.style.display = 'none'
    if (!on) { while (pipSaved.length) { const [el, css] = pipSaved.pop(); el.style.cssText = css } return }
    const v = Array.from(document.querySelectorAll('video')).find(x => !x.paused && x.readyState >= 2)
    if (v) { pipOn(v); if (window.parent !== window) window.parent.postMessage({ __aibPipFrame: true }, '*') }
  }
  window.addEventListener('message', (e) => {
    if (!e.data) return
    if (e.data.__aibPip !== undefined && e.source === window.parent) window.__aibPip(e.data.__aibPip)
    if (e.data.__aibPipFrame) {
      const f = Array.from(document.querySelectorAll('iframe')).find(x => x.contentWindow === e.source)
      if (f) { pipOn(f); if (window.parent !== window) window.parent.postMessage({ __aibPipFrame: true }, '*') }
    }
  })
  // auto: a real movie (>= 10 min) that starts playing moves to the native AI player (once per video)
  document.addEventListener('play', (e) => {
    const v = e.target
    if (!(v instanceof HTMLVideoElement) || v.__aibAuto || !cfg.autoNative || !NATIVE || !NATIVE.autoNative || v.mediaKeys) return
    const go = () => {
      if (v.__aibAuto || v.paused) return
      const r = v.getBoundingClientRect()
      if (!(v.duration >= 600) || r.width < 200) return
      v.__aibAuto = true
      try { if (NATIVE.autoNative(v.currentSrc || v.src || '', v.currentTime || 0)) v.pause() } catch (err) { /* ignore */ }
    }
    if (v.duration >= 600) go(); else v.addEventListener('durationchange', go, { once: true })
  }, true)
  // tell the app when a video plays here (enables PiP on Home and the ad-redirect guard)
  let reported = false
  setInterval(() => {
    if (!NATIVE || (!videoEls.length && !reported)) return
    const v = Array.from(videoEls).find(x => !x.paused && !x.ended && x.readyState >= 2 && x.videoWidth > 0)
    if (v) { NATIVE.playing(true, v.videoWidth, v.videoHeight); reported = true } else if (reported) { NATIVE.playing(false, 0, 0); reported = false }
    // where the film is, so reopening the browser after Android closed it continues from there
    if (v && v.duration >= 120 && NATIVE.position) try { NATIVE.position(v.currentTime, v.duration) } catch (err) { /* ignore */ }
  }, 1500)

  function updateFsButton () {
    if (locked) return
    if (!videoEls.length && ctl.style.display === 'none' && fsBtn.style.display === 'none') return
    let best = null; let area = 0
    Array.prototype.forEach.call(videoEls, v => {
      const r = v.getBoundingClientRect()
      if (r.width < 200 || r.height < 110 || r.bottom <= 0 || r.top >= window.innerHeight || v.readyState < 2 || v.ended) return
      if (r.width * r.height > area) { best = v; area = r.width * r.height }
    })
    const inFs = !!(document.fullscreenElement || document.webkitFullscreenElement)
    if (!best || performance.now() - lastTouch > 3500 && !best.paused) { ctl.style.display = 'none'; fsBtn.style.display = 'none'; return }
    fsTarget = best
    const r = best.getBoundingClientRect(); const top = Math.max(8, r.top + 8); const right = Math.min(window.innerWidth - 8, r.right - 8)
    const parent = document.fullscreenElement || document.webkitFullscreenElement || document.documentElement
    if (ctl.parentNode !== parent) parent.appendChild(ctl)
    fsBtn.textContent = inFs ? '✕' : '⛶'
    const extra = cfg.gestures === true // the site's own player controls are used; our extra buttons only if switched on
    speedBtn.style.display = pipBtn.style.display = extra ? 'block' : 'none'
    lockBtn.style.display = extra && inFs ? 'block' : 'none'
    nativeBtn.style.display = NATIVE && NATIVE.openNative ? 'block' : 'none'
    speedBtn.textContent = best.playbackRate + '×'
    fsBtn.style.display = extra ? 'block' : 'none'; ctl.style.display = 'flex'
    ctl.style.right = (window.innerWidth - right) + 'px'; ctl.style.top = top + 'px'
    fsBtn.style.left = Math.max(8, right - 44) + 'px'; fsBtn.style.top = top + 'px' // reported for diagnostics/tests
  }
  setInterval(updateFsButton, 400)

  /* ---------------- orchestration ---------------- */
  function scan () {
    if (!cfg.enabled || state.failed) return
    document.querySelectorAll('video').forEach(attach)
    if (cfg.images) document.querySelectorAll('img').forEach(i => { if (!imgQueue.includes(i)) imgQueue.push(i) })
    pumpImages()
  }
  const imgQueue = []; let imgDone = 0; let imgPumping = false
  function pumpImages () { // one image per 120ms, max 40 per page: keeps scrolling and playback smooth
    if (imgPumping) return
    imgPumping = true
    const step = () => {
      const img = imgQueue.shift()
      if (!img || imgDone >= 40 || !cfg.images || !cfg.enabled) { imgPumping = false; imgQueue.length = 0; return }
      const did = upscaleImage(img); if (did) imgDone++
      setTimeout(step, did ? 120 : 0)
    }
    step()
  }
  let timer = null
  new window.MutationObserver(() => { clearTimeout(timer); timer = setTimeout(scan, 400) }).observe(document, { childList: true, subtree: true })
  document.addEventListener('visibilitychange', () => { if (!document.hidden) state.videos.forEach((_, v) => loop(v)) })
  const CFG_KEYS = ['enabled', 'target', 'sharpness', 'neural', 'denoise', 'tone', 'images', 'gestures', 'autoNative']
  function forward (c) { // push the config into every child frame (players live in iframes)
    for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage({ __aibCfg: c }, '*') } catch (e) { /* ignore */ } }
  }
  window.addEventListener('message', (e) => {
    if (e.source !== window.parent || !e.data || !e.data.__aibCfg) return
    const c = {}; for (const k of CFG_KEYS) if (k in e.data.__aibCfg) c[k] = e.data.__aibCfg[k]
    window.__minEnhanceSet(c)
  })
  window.__minEnhanceSet = function (c) {
    if (c && c.neural === true) { guard.noNeural = false; guard.off = false; guard.strikes = 0 }
    Object.assign(cfg, c || {}); forward(c || {})
    if (cfg.enabled) { scan(); state.videos.forEach((_, v) => loop(v)) } else Array.from(state.videos.keys()).forEach(detach)
  }
  window.__minEnhance = { cfg, state }
  log('init', window.top === window ? 'TOP' : 'FRAME', location.host, 'enabled=' + cfg.enabled, 'neural=' + cfg.neural, 'target=' + (cfg.target || 'auto'))
  if (window.top === window && navigator.gpu) navigator.gpu.requestAdapter().then(a => log('webgpu adapter:', a ? 'ok' : 'none')).catch(e => log('webgpu adapter error', String(e)))
  scan()
})()
