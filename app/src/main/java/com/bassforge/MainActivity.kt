package com.bassforge

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bassforge.audio.BassProcessor
import com.bassforge.audio.EQPresets
import com.bassforge.audio.EffectService
import com.bassforge.audio.SessionHub
import com.bassforge.audio.SpectrumTap
import com.bassforge.ui.EqualizerView
import com.bassforge.ui.FidgetKnobView
import com.bassforge.ui.GlassNavBar
import com.bassforge.ui.Ico
import com.bassforge.ui.IntroOverlayView
import com.bassforge.ui.LogoView
import com.bassforge.ui.NeoButton
import com.bassforge.ui.NeoCard
import com.bassforge.ui.NeoLabel
import com.bassforge.ui.NeoSegmented
import com.bassforge.ui.NeoSlider
import com.bassforge.ui.NeoSwitch
import com.bassforge.ui.SpectrumView
import com.bassforge.ui.SwatchPicker
import com.bassforge.util.ThemeManager
import com.bassforge.util.Themed
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SessionHub.Listener {

    private companion object {
        /** The title sequence plays once per process, not on every return to the app. */
        var introPlayed = false
        const val UI_PREFS = "bassforge_ui"
        const val KEY_ASKED_NOTIF = "asked_notifications"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var app: BassApp
    private lateinit var processor: BassProcessor

    private lateinit var root: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var nav: GlassNavBar
    private lateinit var logo: LogoView
    private lateinit var status: NeoLabel
    private lateinit var powerBtn: NeoButton
    private var intro: IntroOverlayView? = null

    private lateinit var knob: FidgetKnobView
    private lateinit var modeSeg: NeoSegmented
    private lateinit var spectrumView: SpectrumView
    private lateinit var subSlider: NeoSlider
    private lateinit var punchSlider: NeoSlider
    private lateinit var loudSlider: NeoSlider
    private lateinit var permCard: NeoCard
    private lateinit var eq: EqualizerView
    private val presetButtons = ArrayList<Pair<EQPresets.Preset, NeoButton>>()

    private lateinit var themeSeg: NeoSegmented
    private lateinit var accentPicker: SwatchPicker
    private lateinit var finishPicker: SwatchPicker
    private lateinit var swHaptics: NeoSwitch
    private lateinit var swIntro: NeoSwitch
    private lateinit var swSpectrum: NeoSwitch
    private lateinit var swKeepAlive: NeoSwitch

    private val pages = ArrayList<ScrollView>()
    private var pageIndex = 0

    private var spectrumTap: SpectrumTap? = null
    private var started = false
    private var lastSpectrumAt = 0L
    private var lastStatus = ""
    private val handler = Handler(Looper.getMainLooper())

    private val procListener: () -> Unit = { runOnUiThread { syncUi() } }
    private val themeListener: () -> Unit = { applyThemeToTree(root) }
    private val darkListener: (Boolean) -> Unit = { applySystemBars(it) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSpectrumTap()
            updatePermCard()
        }

    /** If the spectrum stops delivering frames, settle back to standby. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastSpectrumAt > 500L) {
                knob.beat = 0f
                spectrumView.setStandby()
            }
            handler.postDelayed(this, 300L)
        }
    }

    private fun dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ───────────────────────── creation ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        app = application as BassApp
        processor = app.processor

        root = FrameLayout(this)
        root.setBackgroundColor(ThemeManager.p.bg)
        setContentView(root)

        buildHeader()
        buildPages()
        buildNav()

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(pageHost, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(nav, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(dpi(20f), bars.top + dpi(10f), dpi(16f), dpi(2f))
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom
            nav.requestLayout()
            for (p in pages) {
                val col = p.getChildAt(0)
                col.setPadding(col.paddingLeft, col.paddingTop, col.paddingRight, bars.bottom + dpi(110f))
            }
            insets
        }

        setupControls()
        syncSettings()
        syncUi()
        applySystemBars(ThemeManager.p.dark)
        showPage(0, false)

        if (introPlayed) {
            maybeRequestNotifications()
        } else {
            introPlayed = true
            val ov = IntroOverlayView(this)
            intro = ov
            root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
            header.alpha = 0f
            pageHost.alpha = 0f
            nav.alpha = 0f
            logo.alpha = 0f
            root.post {
                val a = IntArray(2); val b = IntArray(2)
                logo.getLocationInWindow(a); ov.getLocationInWindow(b)
                ov.targetRect = RectF(
                    (a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(),
                    (a[0] - b[0] + logo.width).toFloat(), (a[1] - b[1] + logo.height).toFloat()
                )
            }
            ov.onReveal = { playEntrance() }
            ov.onFinished = {
                logo.alpha = 1f
                root.removeView(ov)
                intro = null
                maybeRequestNotifications()
            }
        }
    }

    // ───────────────────────── building blocks ─────────────────────────

    private fun label(text: String, role: Int = 0, sizeSp: Float = 14f, bold: Boolean = false): NeoLabel =
        NeoLabel(this).also {
            it.text = text
            it.role = role
            it.textSize = sizeSp
            if (bold) it.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            else it.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }

    private fun section(parent: LinearLayout, text: String) {
        val l = label(text.uppercase(Locale.ROOT), 1, 12f, true)
        l.letterSpacing = 0.16f
        parent.addView(l, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = dpi(18f); topMargin = dpi(14f); bottomMargin = dpi(0f)
        })
    }

    private fun card(parent: LinearLayout, style: Int = 0, build: (LinearLayout) -> Unit): NeoCard {
        val c = NeoCard(this)
        c.cardStyle = style
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
        }
        build(inner)
        c.addView(inner, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    private fun page(build: (LinearLayout) -> Unit): ScrollView {
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(4f), dpi(0f), dpi(4f), dpi(120f))
        }
        build(col)
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String, on: Boolean, change: (Boolean) -> Unit): NeoSwitch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6f), dpi(2f), dpi(0f), dpi(2f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 0, 15f, true))
        col.addView(label(sub, 1, 12f))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = NeoSwitch(this)
        sw.setChecked(on, false)
        sw.onCheckedChange = change
        row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return sw
    }

    private fun buildHeader() {
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logo = LogoView(this)
        header.addView(logo, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("BassForge", 0, 22f, true))
        status = label("", 1, 12f).also {
            it.maxLines = 1
            it.ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(12f) })

        powerBtn = NeoButton(this).apply {
            icon = Ico.POWER
            compact = true
            contentDescription = "Power"
        }
        header.addView(powerBtn, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun buildPages() {
        pageHost = FrameLayout(this)

        // ── BASS ──
        pages.add(page { col ->
            permCard = card(col, 2) { inner ->
                inner.addView(label("Live spectrum needs mic permission", 0, 15f, true))
                inner.addView(label(
                    "Android requires it for its audio visualizer. BassForge never records or stores audio. Everything else works without it.",
                    1, 12f
                ))
                val allow = NeoButton(this).apply { kind = NeoButton.Kind.PRIMARY; text = "Allow" }
                allow.setOnClickListener { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                inner.addView(allow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(6f) })
            }

            knob = FidgetKnobView(this)
            col.addView(knob, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                marginStart = dpi(10f); marginEnd = dpi(10f)
            })
            val hint = label("Turn the spinner to set the bass. Push past MAX and let it fly.", 1, 12f)
            hint.gravity = Gravity.CENTER
            col.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(2f); bottomMargin = dpi(6f) })

            modeSeg = NeoSegmented(this).apply { options = listOf("Music", "Movie") }
            col.addView(modeSeg, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dpi(4f); marginEnd = dpi(4f) })

            section(col, "Spectrum")
            spectrumView = SpectrumView(this)
            col.addView(spectrumView, LinearLayout.LayoutParams(MATCH, WRAP))

            section(col, "Tuning")
            card(col, 0) { inner ->
                subSlider = NeoSlider(this)
                punchSlider = NeoSlider(this)
                loudSlider = NeoSlider(this)
                inner.addView(subSlider, LinearLayout.LayoutParams(MATCH, WRAP))
                inner.addView(punchSlider, LinearLayout.LayoutParams(MATCH, WRAP))
                inner.addView(loudSlider, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        })

        // ── EQ ──
        pages.add(page { col ->
            section(col, "Presets")
            val hs = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dpi(6f), 0, dpi(6f), 0)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (preset in EQPresets.ALL) {
                val chip = NeoButton(this)
                chip.text = preset.name
                chip.setOnClickListener { processor.applyPreset(preset) }
                row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
                presetButtons.add(preset to chip)
            }
            hs.addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
            col.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))

            section(col, "10-band equalizer")
            card(col, 0) { inner ->
                eq = EqualizerView(this)
                inner.addView(eq, LinearLayout.LayoutParams(MATCH, WRAP))
                inner.addView(
                    label("Drag a band. Double-tap to flatten it.", 1, 12f).also { it.gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(MATCH, WRAP)
                )
            }
            val reset = NeoButton(this).apply { text = "Flatten all"; icon = Ico.RESET }
            reset.setOnClickListener { processor.applyPreset(EQPresets.FLAT) }
            col.addView(reset, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(4f)
            })
        })

        // ── SETTINGS ──
        pages.add(page { col ->
            section(col, "Appearance")
            card(col, 0) { inner ->
                inner.addView(label("Theme", 0, 15f, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dpi(6f) })
                themeSeg = NeoSegmented(this).apply { options = listOf("System", "Light", "Dark") }
                inner.addView(themeSeg, LinearLayout.LayoutParams(MATCH, WRAP))

                inner.addView(label("Accent colour", 0, 15f, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dpi(6f); topMargin = dpi(6f) })
                accentPicker = SwatchPicker(this)
                inner.addView(accentPicker, LinearLayout.LayoutParams(MATCH, WRAP))

                inner.addView(label("Spinner finish", 0, 15f, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dpi(6f); topMargin = dpi(2f) })
                finishPicker = SwatchPicker(this)
                inner.addView(finishPicker, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            section(col, "Behaviour")
            card(col, 0) { inner ->
                swHaptics = switchRow(inner, "Haptics", "Subtle ticks as you turn and press", ThemeManager.haptics) {
                    ThemeManager.setHaptics(it)
                }
                swIntro = switchRow(inner, "Intro spin", "The spinner whirls when the app opens", ThemeManager.introSpin) {
                    ThemeManager.setIntroSpin(it)
                }
                swSpectrum = switchRow(inner, "Live spectrum", "Needs the mic permission (never records)", ThemeManager.liveSpectrum) {
                    ThemeManager.setLiveSpectrum(it)
                    refreshSpectrumTap()
                    updatePermCard()
                }
                swKeepAlive = switchRow(inner, "Keep running", "Foreground service so it survives in the background", ThemeManager.keepAlive) {
                    ThemeManager.setKeepAlive(it)
                    if (it && processor.enabled) EffectService.start(this) else if (!it) EffectService.stop(this)
                }
            }

            val reset = NeoButton(this).apply { text = "Reset appearance & behaviour"; icon = Ico.RESET }
            reset.setOnClickListener {
                ThemeManager.resetAll(this)
                syncSettings()
                refreshSpectrumTap()
                updatePermCard()
            }
            col.addView(reset, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(4f)
            })

            section(col, "About")
            card(col, 2) { inner ->
                inner.addView(label("BassForge", 0, 20f, true))
                inner.addView(label("Designed and built by", 1, 12f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(6f) })
                inner.addView(label("K. W. Neke", 2, 22f, true))
                inner.addView(
                    label(
                        "Version 1.0\n\nBassForge shapes the audio of supported music and video players on this device. " +
                            "Everything runs locally: no account, no ads, no internet access, and no audio is recorded or uploaded.",
                        1, 12f
                    ),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(8f) }
                )
            }
        })

        for (p in pages) pageHost.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildNav() {
        nav = GlassNavBar(this)
        nav.onTab = { showPage(it, true) }
    }

    // ───────────────────────── wiring ─────────────────────────

    private fun setupControls() {
        powerBtn.setOnClickListener {
            val on = !processor.enabled
            processor.setEnabled(on)
            if (on) { if (ThemeManager.keepAlive) EffectService.start(this) } else EffectService.stop(this)
        }

        knob.onBassChanged = { processor.setBass(it) }

        modeSeg.onSelect = { processor.setMovieMode(it == 1) }

        subSlider.label = "Sub bass"
        subSlider.bipolar = true
        subSlider.formatter = { String.format(Locale.US, "%+.1f dB", (it - 0.5f) * 6f) }
        subSlider.onChange = { processor.setSub(it) }

        punchSlider.label = "Punch"
        punchSlider.bipolar = true
        punchSlider.formatter = { String.format(Locale.US, "%+.1f dB", (it - 0.5f) * 6f) }
        punchSlider.onChange = { processor.setPunch(it) }

        loudSlider.label = "Loudness"
        loudSlider.formatter = { if (it < 0.005f) "Off" else String.format(Locale.US, "+%.1f dB", it * 6f) }
        loudSlider.onChange = { processor.setLoudness((it * 600f).roundToInt()) }

        eq.onBandChanged = { band, gain -> processor.setEqBand(band, gain) }

        themeSeg.onSelect = { ThemeManager.setMode(this, it) }

        accentPicker.colors = ThemeManager.ACCENTS.map { it.c1 }.toIntArray()
        accentPicker.names = ThemeManager.ACCENTS.map { it.name }
        accentPicker.onPick = { ThemeManager.setAccent(this, it) }

        finishPicker.metallic = true
        finishPicker.colors = ThemeManager.FINISHES.map {
            Color.rgb((it.r * 255).toInt(), (it.g * 255).toInt(), (it.b * 255).toInt())
        }.toIntArray()
        finishPicker.names = ThemeManager.FINISHES.map { it.name }
        finishPicker.onPick = { ThemeManager.setFinish(it) }

        updatePermCard()
    }

    /** Pushes the stored settings into the settings controls. */
    private fun syncSettings() {
        themeSeg.select(ThemeManager.mode, false)
        accentPicker.select(ThemeManager.accentIndex)
        finishPicker.select(ThemeManager.finishIndex)
        swHaptics.setChecked(ThemeManager.haptics)
        swIntro.setChecked(ThemeManager.introSpin)
        swSpectrum.setChecked(ThemeManager.liveSpectrum)
        swKeepAlive.setChecked(ThemeManager.keepAlive)
    }

    /** Pushes the processor's state into every control. Setters here never fire callbacks. */
    private fun syncUi() {
        knob.bassLevel = processor.bassLevel
        knob.isActive = processor.enabled
        subSlider.value = processor.subLevel
        punchSlider.value = processor.punchLevel
        loudSlider.value = processor.loudnessMb / 600f
        eq.setGains(processor.eqGains)
        modeSeg.select(if (processor.movieMode) 1 else 0)
        powerBtn.lit = processor.enabled
        for ((preset, chip) in presetButtons) chip.lit = preset.name == processor.presetName
        updateStatus()
    }

    private fun updateStatus() {
        val hub = app.sessions
        val text: String
        val role: Int
        when {
            !processor.enabled -> { text = "Bypassed. Tap power to enable"; role = 1 }
            hub.attachedCount > 0 -> {
                val n = hub.attachedCount
                text = "Active on $n player${if (n == 1) "" else "s"} · ${app.engine.modeName}"
                role = 2
            }
            hub.playing -> { text = "Playing, but that app doesn't share its audio session"; role = 1 }
            else -> { text = "Waiting for a player. Start any music app"; role = 1 }
        }
        knob.isMusicPlaying = hub.playing
        status.role = role
        if (text != lastStatus) {
            lastStatus = text
            status.text = text
        }
    }

    private fun updatePermCard() {
        val need = ThemeManager.liveSpectrum && !hasPermission(Manifest.permission.RECORD_AUDIO)
        permCard.visibility = if (need) View.VISIBLE else View.GONE
    }

    // ───────────────────────── pages ─────────────────────────

    private fun showPage(i: Int, animate: Boolean) {
        val next = pages[i]
        val prev = pages[pageIndex]
        nav.select(i, animate)
        if (!animate || i == pageIndex) {
            for (p in pages) p.visibility = if (p === next) View.VISIBLE else View.GONE
            pageIndex = i
            return
        }
        val dir = if (i > pageIndex) 1 else -1
        val d = resources.displayMetrics.density
        prev.animate().cancel()
        next.animate().cancel()
        prev.animate().alpha(0f).translationX(-dir * 36f * d).setDuration(160L).withEndAction {
            prev.visibility = View.GONE
            prev.translationX = 0f
            prev.alpha = 1f
        }.start()
        next.scrollTo(0, 0)
        next.alpha = 0f
        next.translationX = dir * 36f * d
        next.visibility = View.VISIBLE
        next.animate().alpha(1f).translationX(0f).setStartDelay(90L).setDuration(260L)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        pageIndex = i
    }

    // ───────────────────────── theme ─────────────────────────

    private fun applyThemeToTree(v: View) {
        if (v === root) v.setBackgroundColor(ThemeManager.p.bg)
        if (v is Themed) v.applyTheme()
        v.invalidate()
        if (v is ViewGroup) for (k in 0 until v.childCount) applyThemeToTree(v.getChildAt(k))
    }

    private fun applySystemBars(dark: Boolean) {
        val c = WindowCompat.getInsetsController(window, root)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.refresh(this, true)
    }

    // ───────────────────────── entrance ─────────────────────────

    private fun playEntrance() {
        val d = resources.displayMetrics.density
        for ((k, v) in listOf(header, pageHost, nav).withIndex()) {
            v.translationY = if (v === nav) 40f * d else 24f * d
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(k * 90L).setDuration(650L)
                .setInterpolator(DecelerateInterpolator(2.2f)).start()
        }
        knob.scaleX = 0.8f; knob.scaleY = 0.8f
        knob.animate().scaleX(1f).scaleY(1f).setDuration(900L)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
        if (ThemeManager.introSpin) knob.kick(1500f)
    }

    // ───────────────────────── SessionHub.Listener ─────────────────────────

    override fun onHubChanged() {
        runOnUiThread {
            updateStatus()
            refreshSpectrumTap()
        }
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onStart() {
        super.onStart()
        started = true
        app.sessions.listeners.add(this)
        processor.listeners.add(procListener)
        ThemeManager.listeners.add(themeListener)
        ThemeManager.onDarkChanged.add(darkListener)
        ThemeManager.refresh(this, false)
        syncUi()
        updatePermCard()
        if (processor.enabled && ThemeManager.keepAlive) EffectService.start(this)
        lastSpectrumAt = 0L
        handler.post(watchdog)
        refreshSpectrumTap()
    }

    override fun onStop() {
        started = false
        app.sessions.listeners.remove(this)
        processor.listeners.remove(procListener)
        ThemeManager.listeners.remove(themeListener)
        ThemeManager.onDarkChanged.remove(darkListener)
        handler.removeCallbacks(watchdog)
        spectrumTap?.release()
        super.onStop()
    }

    // ───────────────────────── spectrum ─────────────────────────

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshSpectrumTap() {
        if (!started) return
        if (!ThemeManager.liveSpectrum || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            spectrumTap?.release()
            spectrumView.setStandby()
            knob.beat = 0f
            return
        }
        val tap = spectrumTap ?: SpectrumTap { bars, bass ->
            lastSpectrumAt = System.currentTimeMillis()
            spectrumView.setLevels(bars)
            knob.beat = ((bass - 0.22f) / 0.5f).coerceIn(0f, 1f)
        }.also { spectrumTap = it }
        tap.retarget(app.sessions.primarySession)
    }

    // ───────────────────────── permissions ─────────────────────────

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIF, false)) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
}
