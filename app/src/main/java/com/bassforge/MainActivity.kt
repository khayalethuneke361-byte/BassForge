package com.bassforge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import com.bassforge.ui.CinematicBackgroundView
import com.bassforge.ui.EqualizerView
import com.bassforge.ui.FidgetKnobView
import com.bassforge.ui.IntroOverlayView
import com.bassforge.ui.NeoButton
import com.bassforge.ui.NeoSlider
import com.bassforge.ui.SpectrumView
import com.bassforge.util.NeoColors
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SessionHub.Listener {

    private companion object {
        /** The title sequence plays once per process, not on every return to the app. */
        var introPlayed = false
        const val UI_PREFS = "bassforge_ui"
        const val KEY_ASKED_PERMS = "asked_permissions"
    }

    private lateinit var app: BassApp
    private lateinit var processor: BassProcessor

    private lateinit var bg: CinematicBackgroundView
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var powerBtn: NeoButton
    private lateinit var knob: FidgetKnobView
    private lateinit var spectrumView: SpectrumView
    private lateinit var subSlider: NeoSlider
    private lateinit var punchSlider: NeoSlider
    private lateinit var loudSlider: NeoSlider
    private lateinit var presetRow: LinearLayout
    private lateinit var eq: EqualizerView
    private lateinit var movieBtn: NeoButton
    private lateinit var intro: IntroOverlayView

    private val presetButtons = ArrayList<Pair<EQPresets.Preset, NeoButton>>()
    private var spectrumTap: SpectrumTap? = null
    private var started = false
    private var lastSpectrumAt = 0L
    private var lastStatus = ""
    private val handler = Handler(Looper.getMainLooper())

    private val procListener: () -> Unit = { syncUi() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSpectrumTap()
        }

    /** If the spectrum stops delivering frames, settle the knob and background back to idle. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastSpectrumAt > 500L) {
                knob.beat = 0f
                bg.energy = 0f
                spectrumView.setStandby()
            }
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        app = application as BassApp
        processor = app.processor

        bg = findViewById(R.id.bg)
        content = findViewById(R.id.content)
        status = findViewById(R.id.status)
        powerBtn = findViewById(R.id.powerBtn)
        knob = findViewById(R.id.knob)
        spectrumView = findViewById(R.id.spectrum)
        subSlider = findViewById(R.id.subSlider)
        punchSlider = findViewById(R.id.punchSlider)
        loudSlider = findViewById(R.id.loudSlider)
        presetRow = findViewById(R.id.presetRow)
        eq = findViewById(R.id.eq)
        movieBtn = findViewById(R.id.movieBtn)
        intro = findViewById(R.id.intro)

        val d = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(
                (18 * d).toInt(), bars.top + (12 * d).toInt(),
                (18 * d).toInt(), bars.bottom + (24 * d).toInt()
            )
            insets
        }

        setupControls()
        buildPresetChips()
        syncUi()

        if (introPlayed) {
            (intro.parent as? ViewGroup)?.removeView(intro)
            maybeRequestPermissions()
        } else {
            introPlayed = true
            prepareEntrance()
            intro.onReveal = { playEntrance() }
            intro.onFinished = {
                (intro.parent as? ViewGroup)?.removeView(intro)
                maybeRequestPermissions()
            }
        }
    }

    // ───────────────────────── wiring ─────────────────────────

    private fun setupControls() {
        // Power
        powerBtn.icon = NeoButton.Icon.POWER
        powerBtn.accent = NeoColors.CYAN
        powerBtn.setOnClickListener {
            val on = !processor.enabled
            processor.setEnabled(on)
            if (on) EffectService.start(this) else EffectService.stop(this)
        }

        // Bass knob
        knob.onBassChanged = { processor.setBass(it) }

        // Tuning sliders
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

        // EQ
        eq.onBandChanged = { band, gain -> processor.setEqBand(band, gain) }

        // Movie mode
        movieBtn.setOnClickListener {
            processor.setMovieMode(!processor.movieMode)
            Toast.makeText(
                this,
                if (processor.movieMode) "Movie mode: dialogue clarity on, sub-bass tamed"
                else "Music mode: full bass",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildPresetChips() {
        val d = resources.displayMetrics.density
        for (preset in EQPresets.ALL) {
            val chip = NeoButton(this)
            chip.text = preset.name
            chip.setOnClickListener { processor.applyPreset(preset) }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (46 * d).toInt()
            )
            lp.marginEnd = (4 * d).toInt()
            presetRow.addView(chip, lp)
            presetButtons.add(preset to chip)
        }
    }

    /** Pushes the processor's state into every control. Setters here never fire callbacks. */
    private fun syncUi() {
        knob.bassLevel = processor.bassLevel
        knob.isActive = processor.enabled
        subSlider.value = processor.subLevel
        punchSlider.value = processor.punchLevel
        loudSlider.value = processor.loudnessMb / 600f
        eq.setGains(processor.eqGains)

        powerBtn.active = processor.enabled
        movieBtn.active = processor.movieMode
        movieBtn.icon = if (processor.movieMode) NeoButton.Icon.MOVIE else NeoButton.Icon.MUSIC
        movieBtn.text = if (processor.movieMode) "Movie mode" else "Music mode"
        movieBtn.accent = if (processor.movieMode) NeoColors.MAGENTA else NeoColors.ACCENT

        for ((preset, chip) in presetButtons) chip.active = preset.name == processor.presetName

        bg.level = processor.bassLevel
        bg.active = processor.enabled
        updateStatus()
    }

    private fun updateStatus() {
        val hub = app.sessions
        val text: String
        val color: Int
        when {
            !processor.enabled -> {
                text = "○  Bypassed. Tap power to enable"
                color = NeoColors.TEXT_DIM
            }
            hub.attachedCount > 0 -> {
                val n = hub.attachedCount
                text = "●  Active on $n player${if (n == 1) "" else "s"} · ${app.engine.modeName}"
                color = NeoColors.CYAN
            }
            hub.playing -> {
                text = "●  Music is playing, but that app doesn't share its audio session"
                color = 0xFFFFC857.toInt()
            }
            else -> {
                text = "○  Waiting for a player. Start any music app"
                color = NeoColors.TEXT_DIM
            }
        }
        knob.isMusicPlaying = hub.playing
        status.setTextColor(color)
        if (text != lastStatus) {
            lastStatus = text
            status.text = text
            status.alpha = 0f
            status.animate().alpha(1f).setDuration(300L).start()
        }
    }

    // ───────────────────────── SessionHub.Listener ─────────────────────────

    override fun onHubChanged() {
        updateStatus()
        refreshSpectrumTap()
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onStart() {
        super.onStart()
        started = true
        app.sessions.listeners.add(this)
        processor.listeners.add(procListener)
        syncUi()
        if (processor.enabled) EffectService.start(this)
        lastSpectrumAt = 0L
        handler.post(watchdog)
        refreshSpectrumTap()
    }

    override fun onStop() {
        started = false
        app.sessions.listeners.remove(this)
        processor.listeners.remove(procListener)
        handler.removeCallbacks(watchdog)
        spectrumTap?.release()
        super.onStop()
    }

    // ───────────────────────── spectrum ─────────────────────────

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshSpectrumTap() {
        if (!started) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            spectrumTap?.release()
            spectrumView.setStandby()
            return
        }
        val tap = spectrumTap ?: SpectrumTap { bars, bass ->
            lastSpectrumAt = System.currentTimeMillis()
            spectrumView.setLevels(bars)
            val hit = ((bass - 0.22f) / 0.5f).coerceIn(0f, 1f)
            knob.beat = hit
            bg.energy = hit
        }.also { spectrumTap = it }
        tap.retarget(app.sessions.primarySession)
    }

    // ───────────────────────── permissions ─────────────────────────

    private fun maybeRequestPermissions() {
        val prefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_PERMS, false)) return

        val needed = ArrayList<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Two quick permissions")
            .setMessage(
                "Notifications: keeps BassForge running while you listen, with a Stop button.\n\n" +
                "Microphone: Android requires this for the live spectrum analyser. " +
                "BassForge never records or stores audio. Without it everything still works, " +
                "you just won't see the live spectrum."
            )
            .setPositiveButton("Continue") { _, _ ->
                prefs.edit().putBoolean(KEY_ASKED_PERMS, true).apply()
                permissionLauncher.launch(needed.toTypedArray())
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean(KEY_ASKED_PERMS, true).apply()
            }
            .show()
    }

    // ───────────────────────── cinematic entrance ─────────────────────────

    private fun prepareEntrance() {
        val d = resources.displayMetrics.density
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            v.alpha = 0f
            v.translationY = 40f * d
        }
        knob.scaleX = 0.72f
        knob.scaleY = 0.72f
    }

    private fun playEntrance() {
        for (i in 0 until content.childCount) {
            content.getChildAt(i).animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * 70L)
                .setDuration(700L)
                .setInterpolator(DecelerateInterpolator(2.2f))
                .start()
        }
        knob.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(1000L)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()
        knob.kick(1700f)
    }
}
