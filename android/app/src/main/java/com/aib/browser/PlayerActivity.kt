package com.aib.browser

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Native movie player: hardware video decoding (MediaCodec via ExoPlayer) + neural 4x super-resolution
 * (Real-ESRGAN general x4v3) on the Snapdragon NPU through ONNX Runtime's QNN execution provider.
 * Frames that the NPU cannot finish in time are simply replaced by newer ones, so audio is never disturbed.
 */
class PlayerActivity : Activity() {
    companion object {
        const val IN_W = 640; const val IN_H = 360; const val OUT_W = IN_W * 4; const val OUT_H = IN_H * 4
        val log = ArrayDeque<String>()
        fun addLog(s: String) = synchronized(log) { log.addLast(s); while (log.size > 60) log.removeFirst() }
    }

    private lateinit var player: ExoPlayer
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var aiBtn: TextView
    private lateinit var controls: View
    private lateinit var toast: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var renderer: Renderer? = null
    @Volatile private var aiWanted = true
    private var page = ""
    private val startedAt = System.currentTimeMillis()
    private var retries = 0
    private var dragging = false
    private var everPlayed = false
    private var failed = false
    private var autoQuality = true
    private lateinit var spinner: android.widget.ProgressBar
    private lateinit var directView: SurfaceView
    private var hdrDirect = false
    private lateinit var eqBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var ccBtn: TextView
    private lateinit var subView: TextView
    private var eq: android.media.audiofx.Equalizer? = null
    private var loud: android.media.audiofx.LoudnessEnhancer? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(c: Int, r: Int = 22) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun btn(t: String, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = pill(0xAA141414.toInt())
        setPadding(dp(12), 0, dp(12), 0); setOnClickListener { f(); showControls() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val ua = intent.getStringExtra("ua") ?: ""
        val headers = HashMap<String, String>()
        intent.getStringExtra("referer")?.takeIf { it.isNotEmpty() }?.let { headers["Referer"] = it; headers["Origin"] = Uri.parse(it).let { u -> "${u.scheme}://${u.host}" } }
        intent.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }

        page = intent.getStringExtra("page") ?: ""
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        // HDR videos go straight from the decoder to the screen (keeps real HDR); shown only when such a video plays
        directView = SurfaceView(this).apply { visibility = View.GONE }
        root.addView(directView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        status = TextView(this).apply { textSize = 12f; setTextColor(0xFFB9A8FF.toInt()); setPadding(dp(10), dp(4), dp(10), dp(4)); background = pill(0x88000000.toInt(), 12) }
        title = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setSingleLine(); text = intent.getStringExtra("title") ?: Uri.parse(url).host }
        playBtn = btn("⏸") { player.playWhenReady = !player.playWhenReady }.apply { textSize = 28f }
        val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        speedBtn = btn("1×") { val i = (SPEEDS.indexOfFirst { it == player.playbackParameters.speed } + 1) % SPEEDS.size; player.setPlaybackSpeed(SPEEDS[i]); speedBtn.text = "${SPEEDS[i]}×" }
        qualityBtn = btn("تلقائي") { pickQuality() }
        ccBtn = btn("CC") { pickSubtitles() }
        eqBtn = btn(SOUND_LABELS[0]) { setSound((soundMode + 1) % SOUND_LABELS.size) }
        aiBtn = btn("AI ✓") { aiWanted = !aiWanted; renderer?.aiWanted = aiWanted; aiBtn.text = if (aiWanted) "AI ✓" else "AI ✗" }
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // while dragging only the time label follows the finger; one seek when the finger lifts (no stutter on slow networks)
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && player.duration > 0) time.text = "  ${fmt(player.duration * p / 1000)} / ${fmt(player.duration)}" }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true; ui.removeCallbacks(hideControls) }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false; if (player.duration > 0) player.seekTo(player.duration * s.progress / 1000); showControls() }
            })
        }
        time = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), 0) }
        top.addView(btn("‹") { finish() }.apply { textSize = 24f }, LinearLayout.LayoutParams(dp(48), dp(44)))
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, dp(12), 0) })
        top.addView(ccBtn, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        top.addView(qualityBtn, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        top.addView(eqBtn, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        top.addView(aiBtn, LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        top.addView(speedBtn, LinearLayout.LayoutParams(-2, dp(40)))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), dp(14)) }
        bottom.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(time, LinearLayout.LayoutParams(-2, -2))
        val overlay = FrameLayout(this)
        overlay.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        overlay.addView(playBtn, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        overlay.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        overlay.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(20), 0, 0, dp(64)) })
        controls = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        spinner = android.widget.ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        toast = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); background = pill(0xAA000000.toInt()); setPadding(dp(18), dp(10), dp(18), dp(10)); visibility = View.GONE }
        root.addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        subView = TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setBackgroundColor(0x66000000); setPadding(dp(10), dp(3), dp(10), dp(3)); visibility = View.GONE
        }
        root.addView(subView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28); marginStart = dp(40); marginEnd = dp(40) })
        setContentView(root)

        val http = DefaultHttpDataSource.Factory().setUserAgent(ua).setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        // keep downloading continuously up to 10 minutes ahead of what you watch (min = max, so it never pauses loading),
        // so a weak or dropping connection does not stop the movie; playback starts as soon as 1.5 s is ready
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(10 * 60_000, 10 * 60_000, 1_500, 3_000)
            .setTargetBufferBytes(300 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(2 * 60_000, true) // seeking back up to 2 minutes is instant
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build()
        // behave like a real media app: pause for calls / other audio, pause when headphones are unplugged
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        setupSound()
        // subtitle files found on the page; Arabic is preferred, then whatever the site marked
        val subUrls = intent.getStringArrayListExtra("subs") ?: arrayListOf()
        val isAr = { u: String -> Regex("(?i)(^|[^a-z])(ar|ara|arabic|عربي)([^a-z]|$)").containsMatchIn(Uri.decode(u).substringBefore("?").substringAfterLast("/")) }
        val firstPick = subUrls.firstOrNull(isAr) ?: subUrls.firstOrNull()
        val subConfigs = subUrls.map { u ->
            val name = Uri.decode(Uri.parse(u).lastPathSegment ?: "subtitle")
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                .setMimeType(if (u.substringBefore('?').endsWith(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setLanguage(if (isAr(u)) "ar" else null).setLabel(name.substringBeforeLast('.'))
                .setSelectionFlags(if (u == firstPick) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        if (subUrls.isNotEmpty()) addLog("[player] subtitles from page: ${subUrls.size}")
        val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subConfigs)
            .apply { if (url.contains(".m3u8", true) || intent.getBooleanExtra("hls", false)) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("ar").setSelectUndeterminedTextLanguage(true).build()
        player.setMediaItem(item, intent.getLongExtra("pos", 0L).coerceAtLeast(0L))
        player.prepare(); player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playBtn.text = if (isPlaying) "⏸" else "▶"; if (isPlaying) { retries = 0; everPlayed = true } }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                val t = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                subView.text = t; subView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            }
            override fun onRenderedFirstFrame() { everPlayed = true }
            override fun onPlaybackStateChanged(state: Int) {
                val buffering = state == Player.STATE_BUFFERING
                spinner.visibility = if (buffering) View.VISIBLE else View.GONE
                playBtn.visibility = if (buffering) View.INVISIBLE else View.VISIBLE
                if (state == Player.STATE_ENDED) { Resume.remove(this@PlayerActivity, page); finish() }
            }
            override fun onPlayerError(error: PlaybackException) {
                addLog("[player] error ${error.errorCodeName} ${error.message}")
                // network dropped or the server hiccuped: try again from the same second, waiting a bit longer each time
                // only errors that a retry can fix: a dead link (404/403, file missing) is reported at once
                val c = error.errorCode
                val transient = c == PlaybackException.ERROR_CODE_IO_UNSPECIFIED || c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || c == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                    (c == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && retries < 2) // 5xx server hiccup: try twice
                if (transient && retries < 8) {
                    val wait = 2000L * (1 shl Math.min(retries, 4)); retries++
                    status.text = "انقطع الاتصال، إعادة المحاولة خلال ${wait / 1000} ث…"
                    ui.postDelayed({
                        if (isFinishing) return@postDelayed
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) player.seekToDefaultPosition()
                        player.prepare(); player.playWhenReady = true
                    }, wait)
                } else if (!everPlayed) giveUp("${error.errorCodeName}")
                else status.text = if (c == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || c == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) "انتهت صلاحية رابط الفيديو، ارجع للصفحة وشغّله من جديد" else "خطأ في التشغيل: ${error.errorCodeName}"
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val f = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                val tr = f?.colorInfo?.colorTransfer
                val hdr = tr == C.COLOR_TRANSFER_ST2084 || tr == C.COLOR_TRANSFER_HLG
                if (hdr && !hdrDirect && displayHdr()) useDirectHdr()
            }
            override fun onVideoSizeChanged(size: VideoSize) { renderer?.setVideoSize(size.width, size.height, size.pixelWidthHeightRatio); if (hdrDirect) fitDirect() }
        })

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (hdrDirect) return
                renderer = Renderer(this@PlayerActivity, h.surface) { s -> ui.post { status.text = s } }.also {
                    it.aiWanted = aiWanted
                    it.start { videoSurface -> ui.post { player.setVideoSurface(videoSurface) } }
                    val vs = player.videoSize; if (vs.width > 0) it.setVideoSize(vs.width, vs.height, vs.pixelWidthHeightRatio)
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { renderer?.setViewSize(w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) { if (!hdrDirect) player.clearVideoSurface(); renderer?.stop(); renderer = null }
        })
        setupGestures(root)
        tickUi(); showControls()
    }

    /** The native player could not start this video (protected stream, wrong address...): go back, the page keeps playing it. */
    private fun giveUp(why: String) {
        if (failed || isFinishing) return
        failed = true; addLog("[player] gave up before the first frame: $why")
        finish()
    }
    override fun finish() {
        if (::player.isInitialized) setResult(RESULT_OK, Intent().putExtra("pos", player.currentPosition).putExtra("failed", failed))
        super.finish()
    }

    private val hideControls = Runnable { controls.visibility = View.GONE }
    private fun showControls() { controls.visibility = View.VISIBLE; ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 3500) }
    private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun tickUi() {
        if (isFinishing) return
        val d = player.duration
        if (d > 0 && !dragging) {
            seek.progress = (player.currentPosition * 1000 / d).toInt(); seek.secondaryProgress = (player.bufferedPosition * 1000 / d).toInt()
            time.text = "  ${fmt(player.currentPosition)} / ${fmt(d)}"
        }
        if (autoQuality) { val h = player.videoSize.height; qualityBtn.text = if (h > 0) "تلقائي ${h}p" else "تلقائي" }
        // still nothing on screen after 30 s: this address does not work here, hand the video back to the page
        if (!everPlayed && !failed && player.playbackState == Player.STATE_BUFFERING && System.currentTimeMillis() - startedAt > 30_000) giveUp("timeout")
        ui.postDelayed({ tickUi() }, 500)
    }
    private fun flash(t: String) { toast.text = t; toast.visibility = View.VISIBLE; ui.removeCallbacks(hideToast); ui.postDelayed(hideToast, 700) }
    private val hideToast = Runnable { toast.visibility = View.GONE }

    /** double tap left/right = -/+10 s, vertical swipe left = brightness, right = volume, single tap = controls */
    private fun setupGestures(root: View) {
        var lastTap = 0L; var acc = 0; var x0 = 0f; var y0 = 0f; var swiping = 0; var startVal = 0f
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; swiping = 0 }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - y0
                    if (swiping == 0 && Math.abs(dy) > dp(24) && Math.abs(dy) > 2 * Math.abs(e.x - x0)) {
                        swiping = if (x0 < v.width / 2) 1 else 2; y0 = e.y
                        startVal = if (swiping == 1) window.attributes.screenBrightness.let { if (it < 0) 0.5f else it } else am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                    }
                    if (swiping != 0) {
                        val nv = (startVal - (e.y - y0) / (v.height * 0.8f)).coerceIn(0.01f, 1f)
                        if (swiping == 1) { window.attributes = window.attributes.also { it.screenBrightness = nv }; flash("☀ ${(nv * 100).toInt()}%") }
                        else { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(nv * maxVol), 0); flash("🔊 ${(nv * 100).toInt()}%") }
                    }
                }
                MotionEvent.ACTION_UP -> if (swiping == 0) {
                    val now = System.currentTimeMillis(); val rel = e.x / v.width
                    if (now - lastTap < 320 && (rel < 0.38f || rel > 0.62f)) {
                        val d = if (rel < 0.38f) -10 else 10
                        acc = if (Math.signum(acc.toFloat()) == Math.signum(d.toFloat())) acc + d else d
                        player.seekTo((player.currentPosition + d * 1000L).coerceIn(0L, Math.max(0L, player.duration)))
                        flash((if (d < 0) "⏪ " else "⏩ ") + Math.abs(acc) + "ث"); lastTap = now
                    } else {
                        lastTap = now; acc = 0
                        ui.postDelayed({ if (System.currentTimeMillis() - lastTap >= 300) { if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls() } }, 330)
                    }
                }
            }
            true
        }
    }

    override fun onPause() { super.onPause(); saveResume(); if (!isInPictureInPictureMode) player.pause() }
    private fun saveResume() { if (::player.isInitialized && player.duration > 0) Resume.save(this, page, title.text.toString(), player.currentPosition, player.duration) }

    /* ---------- HDR: the decoder draws straight to the screen, so HDR10/HLG/Dolby Vision stay real HDR ---------- */
    @Suppress("DEPRECATION")
    private fun displayHdr(): Boolean = try { windowManager.defaultDisplay.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true } catch (e: Exception) { false }
    private fun useDirectHdr() {
        hdrDirect = true
        aiBtn.visibility = View.GONE // HDR sources are already high resolution; the AI path does not apply
        addLog("[player] HDR video: direct output (AI upscaling not needed for HDR sources)")
        try { window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR } catch (e: Exception) { }
        directView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { player.setVideoSurfaceHolder(h); renderer?.stop(); renderer = null; surfaceView.visibility = View.GONE; status.text = "HDR ✓" }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        directView.visibility = View.VISIBLE
        fitDirect()
    }
    /** keep the real picture shape on the direct surface */
    private fun fitDirect() {
        val vs = player.videoSize; val root = directView.parent as View
        if (vs.width <= 0 || root.width <= 0) { root.post { if (player.videoSize.width > 0) fitDirect() }; return }
        val a = vs.width * vs.pixelWidthHeightRatio / vs.height
        var w = root.width; var hh = (w / a).toInt()
        if (hh > root.height) { hh = root.height; w = (hh * a).toInt() }
        directView.layoutParams = FrameLayout.LayoutParams(w, hh, Gravity.CENTER)
    }

    /* ---------- subtitles: from the page (.vtt/.srt) or inside the stream ---------- */
    private fun pickSubtitles() {
        val names = arrayListOf("بدون ترجمة"); val acts = arrayListOf<() -> Unit>({
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            ccBtn.text = "CC ✗"
        })
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                names.add(listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage }).distinct().joinToString(" · ").ifBlank { "ترجمة ${names.size}" })
                acts.add {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    ccBtn.text = "CC"
                }
            }
        }
        if (names.size == 1) { flash("لم أجد ترجمة لهذا الفيديو. شغّل الترجمة في مشغّل الموقع أولاً ثم افتح AI ▶"); return }
        android.app.AlertDialog.Builder(this).setTitle("الترجمة").setItems(names.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    /* ---------- stream quality: automatic, or a fixed height when the site offers several ---------- */
    private fun pickQuality() {
        val opts = ArrayList<Pair<String, (() -> Unit)>>()
        opts.add("تلقائي" to {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            autoQuality = true
        })
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            val idx = (0 until g.length).filter { g.isTrackSupported(it) }.sortedByDescending { g.getTrackFormat(it).height }
            for (i in idx) {
                val h = g.getTrackFormat(i).height; if (h <= 0 || opts.any { it.first == "${h}p" }) continue
                opts.add("${h}p" to {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    qualityBtn.text = "${h}p"; autoQuality = false
                })
            }
        }
        if (opts.size == 1) { flash("الموقع يوفّر جودة واحدة فقط"); return }
        android.app.AlertDialog.Builder(this).setTitle("الجودة").setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    /* ---------- sound: normal / clear dialogue / night (quiet explosions, loud voices) ---------- */
    private val SOUND_LABELS = arrayOf("🎧 عادي", "🗣 حوار", "🌙 ليلي")
    private var soundMode = 0
    private fun setupSound() {
        try {
            val id = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            player.audioSessionId = id
            eq = android.media.audiofx.Equalizer(0, id)
            loud = android.media.audiofx.LoudnessEnhancer(id)
        } catch (e: Throwable) { addLog("[player] audio effects unavailable: ${e.message}") }
        setSound(getSharedPreferences("enhance", Context.MODE_PRIVATE).getInt("sound", 0))
    }
    private fun setSound(m: Int) {
        soundMode = m.coerceIn(0, SOUND_LABELS.size - 1); eqBtn.text = SOUND_LABELS[soundMode]
        getSharedPreferences("enhance", Context.MODE_PRIVATE).edit().putInt("sound", soundMode).apply()
        try {
            eq?.let { e ->
                val range = e.bandLevelRange // millibels
                for (b in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000
                    val mb = when (soundMode) {
                        1 -> when { hz < 150 -> -600; hz < 400 -> -200; hz < 1000 -> 200; hz < 5000 -> 600; else -> 200 } // voices forward
                        2 -> when { hz < 150 -> -900; hz < 400 -> -400; hz < 1000 -> 200; hz < 5000 -> 500; else -> 0 }   // night: less boom
                        else -> 0
                    }
                    e.setBandLevel(b.toShort(), mb.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                e.setEnabled(soundMode != 0)
            }
            loud?.let { it.setTargetGain(when (soundMode) { 1 -> 400; 2 -> 900; else -> 0 }); it.setEnabled(soundMode != 0) }
        } catch (e: Throwable) { addLog("[player] sound mode failed: ${e.message}") }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) try {
            val vs = player.videoSize
            val r = if (vs.width > 0 && vs.height > 0) (vs.width * vs.pixelWidthHeightRatio / vs.height).coerceIn(0.42f, 2.39f) else 16f / 9f
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((r * 1000).toInt(), 1000)).build())
        } catch (e: Exception) { }
    }
    override fun onPictureInPictureModeChanged(inPip: Boolean, c: android.content.res.Configuration) { super.onPictureInPictureModeChanged(inPip, c); controls.visibility = if (inPip) View.GONE else View.VISIBLE }
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        saveResume()
        try { eq?.release(); loud?.release() } catch (e: Throwable) { }
        if (::player.isInitialized) player.release()
        renderer?.stop(); renderer = null
        super.onDestroy()
    }

    /* ======================================================================================== */

    class Renderer(private val ctx: Context, private val out: Surface, private val onStatus: (String) -> Unit) : SurfaceTexture.OnFrameAvailableListener {
        @Volatile var aiWanted = true
        private val thread = HandlerThread("ai-render").apply { start() }
        private val h = Handler(thread.looper)
        private var egl: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurf: EGLSurface = EGL14.EGL_NO_SURFACE
        private var oesTex = 0; private var st: SurfaceTexture? = null; private var videoSurface: Surface? = null
        private var progOes = 0; private var progPlanes = 0; private var progDirect = 0
        private var fbo = 0; private var fboTex = 0; private var outTex = 0
        private var viewW = 1; private var viewH = 1; @Volatile private var vidW = 0; @Volatile private var vidH = 0; @Volatile private var par = 1f
        private val stMatrix = FloatArray(16)
        // neural
        private var env: OrtEnvironment? = null; @Volatile private var session: OrtSession? = null
        private var inBuf: FloatBuffer? = null; private var outBuf: FloatBuffer? = null
        private var inTensor: OnnxTensor? = null; private var outTensor: OnnxTensor? = null
        private val pixels: ByteBuffer = ByteBuffer.allocateDirect(IN_W * IN_H * 4).order(ByteOrder.nativeOrder())
        private val rgba = IntArray(IN_W * IN_H)
        private val planes = FloatArray(3 * IN_W * IN_H)
        private val LUT = FloatArray(256) { it / 255f }
        // model output packed to RGBA8 on 4 cores: 4x less to upload than 3 float planes (44 MB -> 15 MB a frame) and the GPU filters it itself
        private val outPix: ByteBuffer = ByteBuffer.allocateDirect(OUT_W * OUT_H * 4).order(ByteOrder.nativeOrder())
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        private val tmp = Array(4) { Array(3) { FloatArray(OUT_W * 16) } }
        private val tmpI = Array(4) { IntArray(OUT_W * 16) }
        private val stage = DoubleArray(4); private var stageN = 0
        private fun parallel(n: Int, body: (Int, Int, Int) -> Unit) {
            val step = (n + 3) / 4
            (0 until 4).map { j -> pool.submit(Runnable { val lo = j * step; val hi = Math.min(n, lo + step); if (lo < hi) body(j, lo, hi) }) }.forEach { it.get() }
        }
        private var backend = "loading"; private var emaMs = 0.0; private var slowSince = 0L; private var disabledReason = ""
        private var lastFrameNs = 0L; private var fpsEma = 30.0; private var frames = 0
        private val content = FloatArray(4) // content rect inside the 640x360 model input (u0,v0,u1,v1)

        fun start(onSurface: (Surface) -> Unit) = h.post {
            setupEgl(); setupGl()
            st = SurfaceTexture(oesTex).also { it.setOnFrameAvailableListener(this, h) }
            videoSurface = Surface(st); onSurface(videoSurface!!)
            Thread { loadModel() }.start()
        }
        fun stop() { h.post { try { session?.close(); inTensor?.close(); outTensor?.close() } catch (e: Exception) { }; session = null; st?.release(); videoSurface?.release(); EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); EGL14.eglDestroySurface(egl, eglSurf); EGL14.eglDestroyContext(egl, eglCtx); EGL14.eglTerminate(egl); pool.shutdown(); thread.quitSafely() } }
        fun setViewSize(w: Int, hh: Int) = h.post { viewW = w; viewH = hh }
        fun setVideoSize(w: Int, hh: Int, p: Float) { vidW = w; vidH = hh; par = if (p > 0) p else 1f; h.post { slowSince = 0; emaMs = 0.0; disabledReason = "" } }

        private fun loadModel() {
            try {
                // the NPU (Hexagon HTP) user-space driver finds its DSP-side library through ADSP_LIBRARY_PATH
                android.system.Os.setenv("ADSP_LIBRARY_PATH", ctx.applicationInfo.nativeLibraryDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
                val e = OrtEnvironment.getEnvironment(); env = e
                fun model(n: String) = try { ctx.assets.open("model/$n").use { it.readBytes() } } catch (x: Exception) { null }
                // fastest first: 16-bit/8-bit quantized model on the NPU, then the float model on the NPU, then CPU
                // NPU only: on the CPU this model takes seconds per frame, which froze the picture
                val tries = listOf("npu-a16w8" to "realesr-x4v3-640x360-a16w8.onnx", "npu" to "realesr-x4v3-640x360.onnx")
                for ((t, file) in tries) {
                    val bytes = model(file) ?: continue
                    val ctxFile = java.io.File(ctx.filesDir, "sr_ctx_${t}_${bytes.size}.onnx") // new model in an update = new compiled copy
                    ctx.filesDir.listFiles { f -> (f.name.startsWith("sr_ctx_${t}_") || f.name == "sr_ctx_$t.onnx") && f.name != ctxFile.name }?.forEach { it.delete() }
                    try {
                        fun opts(dump: Boolean) = OrtSession.SessionOptions().apply {
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                            if (t.startsWith("npu")) {
                                // the few layers the NPU cannot run (seen on the phone: the float model) run on the CPU instead of failing the whole model
                                // first run compiles the graph for the NPU (slow) and saves it; later runs load the saved one
                                if (dump) { addConfigEntry("ep.context_enable", "1"); addConfigEntry("ep.context_file_path", ctxFile.absolutePath) }
                                addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "htp_graph_finalization_optimization_mode" to "3") + (if (t == "npu") mapOf("enable_htp_fp16_precision" to "1") else emptyMap()))
                            } else setIntraOpNumThreads(4) // this ORT build has no XNNPACK: plain CPU kernels
                        }
                        val t0 = System.currentTimeMillis()
                        val s = if (t.startsWith("npu") && ctxFile.exists() && ctxFile.length() > 0)
                            try { e.createSession(ctxFile.absolutePath, opts(false)) } catch (x: Exception) { ctxFile.delete(); e.createSession(bytes, opts(true)) }
                        else { ctxFile.delete(); e.createSession(bytes, opts(t.startsWith("npu"))) }
                        addLog("[player] SR session on $t in ${System.currentTimeMillis() - t0}ms")
                        inBuf = ByteBuffer.allocateDirect(3 * IN_W * IN_H * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        outBuf = ByteBuffer.allocateDirect(3 * OUT_W * OUT_H * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        inTensor = OnnxTensor.createTensor(e, inBuf, longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong()))
                        outTensor = OnnxTensor.createTensor(e, outBuf, longArrayOf(1, 3, OUT_H.toLong(), OUT_W.toLong()))
                        // test it before the video ever waits for it: two runs on a blank frame (the first one warms the NPU up)
                        var testMs = 0L
                        for (k in 0 until 2) { val w0 = System.currentTimeMillis(); s.run(mapOf(s.inputNames.first() to inTensor!!), mapOf(s.outputNames.first() to outTensor!!)).close(); testMs = System.currentTimeMillis() - w0 }
                        addLog("[player] SR test run on $t: ${testMs}ms per frame")
                        if (testMs > 60) { // cannot keep up with a 24 fps movie: keep the smooth GPU path instead of stuttering
                            addLog("[player] SR on $t too slow for real time, not used"); s.close(); inTensor?.close(); outTensor?.close(); inTensor = null; outTensor = null
                            disabledReason = "slow"; continue
                        }
                        disabledReason = ""
                        backend = t; session = s
                        break
                    } catch (x: Throwable) { addLog("[player] SR on $t failed: ${x.message?.take(200)}"); if (t.startsWith("npu")) ctxFile.delete() }
                }
                if (session == null) backend = "unavailable"
            } catch (x: Throwable) { backend = "error"; addLog("[player] SR load error: ${x.message?.take(200)}") }
        }

        override fun onFrameAvailable(s: SurfaceTexture) {
            try { render() } catch (e: Throwable) { addLog("[player] render error: ${e.message}") }
        }

        private fun render() {
            val tex = st ?: return
            tex.updateTexImage(); tex.getTransformMatrix(stMatrix)
            val now = System.nanoTime(); if (lastFrameNs > 0) { val dt = (now - lastFrameNs) / 1e6; if (dt in 5.0..200.0) fpsEma = fpsEma * 0.9 + (1000 / dt) * 0.1 }; lastFrameNs = now
            val vw = vidW; val vh = vidH
            if (vw <= 0 || vh <= 0) return
            val dispAspect = vw * par / vh
            // neural path only for small sources (<= 540p): bigger ones are already sharp on this screen
            val s = session
            val useAi = aiWanted && s != null && vh <= 540 && disabledReason.isEmpty()
            var ms = 0.0
            if (useAi) {
                val t0 = System.nanoTime()
                // 1) video frame -> 640x360 (aspect kept, black bars), rows top-first for the model
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo); GLES30.glViewport(0, 0, IN_W, IN_H)
                GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                val fit = fitRect(IN_W, IN_H, dispAspect)
                content[0] = fit[0] / IN_W; content[1] = fit[1] / IN_H; content[2] = (fit[0] + fit[2]) / IN_W; content[3] = (fit[1] + fit[3]) / IN_H
                GLES30.glViewport(fit[0].toInt(), fit[1].toInt(), fit[2].toInt(), fit[3].toInt())
                drawOes(progOes, flipY = true)
                GLES30.glReadPixels(0, 0, IN_W, IN_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels.rewind())
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                // 2) RGBA bytes -> NCHW float planes
                // bulk copy into plain arrays first: per-pixel buffer calls cost ~10x more on every frame
                val inb = inBuf!!; val px = IN_W * IN_H; pixels.rewind()
                pixels.asIntBuffer().get(rgba); pixels.rewind()
                val pl = planes; val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                val t1 = System.nanoTime()
                parallel(px) { _, lo, hi ->
                    for (i in lo until hi) {
                        val c = rgba[i]
                        val r = if (little) c and 255 else (c ushr 24) and 255
                        val g = (if (little) c ushr 8 else c ushr 16) and 255
                        val b = (if (little) c ushr 16 else c ushr 8) and 255
                        pl[i] = LUT[r]; pl[px + i] = LUT[g]; pl[2 * px + i] = LUT[b]
                    }
                }
                inb.position(0); inb.put(pl); inb.position(0)
                // 3) NPU inference into the pinned output buffer
                val t2 = System.nanoTime()
                s!!.run(mapOf(s.inputNames.first() to inTensor!!), mapOf(s.outputNames.first() to outTensor!!)).close()
                // 4) float planes -> RGBA8 (4 cores, 16 rows at a time), one 15 MB upload, GPU bilinear
                val t3 = System.nanoTime()
                val ob = outBuf!!; val n = OUT_W * OUT_H; val opx = outPix.asIntBuffer()
                parallel(OUT_H) { j, r0, r1 ->
                    val src = ob.duplicate(); val dst = opx.duplicate(); val tr = tmp[j]; val ti = tmpI[j]
                    var row = r0
                    while (row < r1) {
                        val rows = Math.min(16, r1 - row); val off = row * OUT_W; val len = rows * OUT_W
                        for (c in 0 until 3) { src.position(c * n + off); src.get(tr[c], 0, len) }
                        val rr = tr[0]; val gg = tr[1]; val bb = tr[2]
                        for (i in 0 until len) {
                            val r = (rr[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val g = (gg[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val b = (bb[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                            ti[i] = if (little) r or (g shl 8) or (b shl 16) or (255 shl 24) else (r shl 24) or (g shl 16) or (b shl 8) or 255
                        }
                        dst.position(off); dst.put(ti, 0, len)
                        row += rows
                    }
                }
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, OUT_W, OUT_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, outPix.rewind())
                drawToScreen(progPlanes, dispAspect)
                val t4 = System.nanoTime()
                stage[0] += (t1 - t0) / 1e6; stage[1] += (t2 - t1) / 1e6; stage[2] += (t3 - t2) / 1e6; stage[3] += (t4 - t3) / 1e6
                if (++stageN == 120) { addLog("[player] SR per frame: read ${"%.1f".format(stage[0] / 120)}ms, in ${"%.1f".format(stage[1] / 120)}ms, NPU ${"%.1f".format(stage[2] / 120)}ms, out ${"%.1f".format(stage[3] / 120)}ms"); stage.fill(0.0); stageN = 0 }
                ms = (System.nanoTime() - t0) / 1e6
                emaMs = if (emaMs == 0.0) ms else emaMs * 0.85 + ms * 0.15
                // NPU too slow for this video's frame rate for 3 s -> stay smooth, fall back to the GPU shader
                val budget = 1000.0 / Math.max(fpsEma, 1.0)
                if (emaMs > budget * 1.15 && ++frames > 5) { if (slowSince == 0L) slowSince = now else if (now - slowSince > 1_000_000_000L || ms > budget * 4) { disabledReason = "slow"; addLog("[player] SR too slow (${emaMs.toInt()}ms > ${budget.toInt()}ms), using GPU shader") } } else slowSince = 0L
            } else drawToScreen(progDirect, dispAspect)
            EGL14.eglSwapBuffers(egl, eglSurf)
            if (frames % 15 == 0) onStatus(statusText(vw, vh, useAi))
            frames++
        }

        private fun statusText(vw: Int, vh: Int, ai: Boolean): String {
            val b = when (backend) { "npu-a16w8" -> "NPU (int)"; "npu" -> "NPU"; "cpu" -> "CPU"; "loading" -> "…"; else -> backend }
            return when {
                !aiWanted -> "${vw}×${vh}  AI مطفأ"
                ai -> "✨ ${vw}×${vh} → 4× عصبي على $b  ${emaMs.toInt()}ms"
                disabledReason == "slow" -> "${vw}×${vh}  العصبي أبطأ من الفيديو (${emaMs.toInt()}ms) → تحسين GPU"
                vh > 540 -> "${vw}×${vh}  مصدر عالي الدقة → تحسين GPU"
                backend == "loading" -> "${vw}×${vh}  تحميل النموذج العصبي…"
                else -> "${vw}×${vh}  العصبي غير متاح ($b) → تحسين GPU"
            }
        }

        private fun fitRect(w: Int, hh: Int, aspect: Float): FloatArray {
            var cw = w.toFloat(); var ch = cw / aspect
            if (ch > hh) { ch = hh.toFloat(); cw = ch * aspect }
            return floatArrayOf((w - cw) / 2f, (hh - ch) / 2f, cw, ch)
        }

        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        private fun drawOes(p: Int, flipY: Boolean) {
            GLES30.glUseProgram(p)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "tex"), 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "stm"), 1, false, stMatrix, 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "flip"), if (flipY) 1f else 0f)
            drawQuad(p)
        }
        private fun drawQuad(p: Int) {
            val loc = GLES30.glGetAttribLocation(p, "pos")
            GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        private fun drawToScreen(p: Int, aspect: Float) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewW, viewH); GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val f = fitRect(viewW, viewH, aspect)
            GLES30.glViewport(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt())
            if (p == progDirect) {
                GLES30.glUseProgram(p)
                GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "texel"), 1f / Math.max(vidW, 1), 1f / Math.max(vidH, 1))
                drawOes(p, flipY = false)
            } else {
                GLES30.glUseProgram(p)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex); GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "img"), 0)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "crop"), content[0], content[1], content[2], content[3])
                drawQuad(p)
            }
        }

        private fun setupEgl() {
            egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); val v = IntArray(2); EGL14.eglInitialize(egl, v, 0, v, 1)
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1); EGL14.eglChooseConfig(egl, attrs, 0, cfgs, 0, 1, n, 0)
            eglCtx = EGL14.eglCreateContext(egl, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            eglSurf = EGL14.eglCreateWindowSurface(egl, cfgs[0], out, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(egl, eglSurf, eglSurf, eglCtx)
            val wh = IntArray(1); EGL14.eglQuerySurface(egl, eglSurf, EGL14.EGL_WIDTH, wh, 0); viewW = wh[0]; EGL14.eglQuerySurface(egl, eglSurf, EGL14.EGL_HEIGHT, wh, 0); viewH = wh[0]
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) addLog("[player] shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        private fun program(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs)); GLES30.glLinkProgram(p); return p
        }

        private fun setupGl() {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); oesTex = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            // model input FBO
            GLES30.glGenTextures(1, t, 0); fboTex = t[0]; GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, IN_W, IN_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glGenFramebuffers(1, t, 0); fbo = t[0]; GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            // model output as one RGBA8 texture (filterable everywhere: hardware bilinear)
            GLES30.glGenTextures(1, t, 0); outTex = t[0]; GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, OUT_W, OUT_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            val vsOes = """#version 300 es
in vec2 pos; uniform mat4 stm; uniform float flip; out vec2 uv;
void main(){ vec2 t = pos*0.5+0.5; if (flip > 0.5) t.y = 1.0 - t.y; uv = (stm*vec4(t,0.,1.)).xy; gl_Position = vec4(pos,0.,1.); }"""
            progOes = program(vsOes, """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float; uniform samplerExternalOES tex; in vec2 uv; out vec4 o;
void main(){ o = vec4(texture(tex, uv).rgb, 1.); }""")
            // direct path (big sources / AI off): light contrast-adaptive sharpening
            progDirect = program(vsOes, """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float; uniform samplerExternalOES tex; uniform vec2 texel; in vec2 uv; out vec4 o;
void main(){ vec3 c=texture(tex,uv).rgb; vec3 n=texture(tex,uv+vec2(0.,texel.y)).rgb, s=texture(tex,uv-vec2(0.,texel.y)).rgb, e=texture(tex,uv+vec2(texel.x,0.)).rgb, w=texture(tex,uv-vec2(texel.x,0.)).rgb;
  vec3 mn=min(c,min(min(n,s),min(e,w))), mx=max(c,max(max(n,s),max(e,w)));
  vec3 amp=sqrt(clamp(min(mn,2.-mx)/max(mx,1e-4),0.,1.)); vec3 k=-amp/mix(8.,5.,0.5);
  o=vec4(clamp((c+(n+s+e+w)*k)/(1.+4.*k),0.,1.),1.); }""")
            // neural path: model output (rows top-first), cropped to the real picture
            progPlanes = program("""#version 300 es
in vec2 pos; uniform vec4 crop; out vec2 uv;
void main(){ vec2 t = pos*0.5+0.5; t.y = 1.0 - t.y; uv = mix(crop.xy, crop.zw, t); gl_Position = vec4(pos,0.,1.); }""", """#version 300 es
precision mediump float; uniform sampler2D img; in vec2 uv; out vec4 o;
void main(){ o = vec4(texture(img, uv).rgb, 1.); }""")
        }
    }
}
