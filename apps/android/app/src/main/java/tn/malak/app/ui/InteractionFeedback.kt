package tn.malak.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import tn.malak.app.R

internal enum class AppCue(val sound: Int, val haptic: Int) {
    Action(R.raw.amlak_action, HapticFeedbackConstants.VIRTUAL_KEY),
    Radar(R.raw.amlak_radar, HapticFeedbackConstants.CONTEXT_CLICK),
    Step(R.raw.amlak_step, HapticFeedbackConstants.CLOCK_TICK),
    Confirm(R.raw.amlak_confirm, HapticFeedbackConstants.VIRTUAL_KEY)
}

internal val LocalFeedback = staticCompositionLocalOf<(AppCue) -> Unit> { {} }

private class CuePlayer(private val context: Context, private val view: View) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ready = mutableSetOf<Int>()
    private val sounds: Map<AppCue, Int>
    private var lastCue = -1000L

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        sounds = AppCue.entries.associateWith { pool.load(context, it.sound, 1) }
    }

    fun play(cue: AppCue, sound: Boolean, haptic: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!view.hasWindowFocus() || now - lastCue < (if (cue == AppCue.Step) 140L else 70L)) return
        lastCue = now
        if (haptic) view.performHapticFeedback(cue.haptic)
        val id = sounds[cue] ?: return
        if (sound && id in ready && audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0) {
            val volume = if (cue == AppCue.Step) .27f else .43f
            pool.play(id, volume, volume, 1, 0, 1f)
        }
    }

    fun release() { pool.setOnLoadCompleteListener(null); pool.release() }
}

@Composable
internal fun rememberAppFeedback(sound: Boolean, haptic: Boolean): (AppCue) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    var player by remember { mutableStateOf<CuePlayer?>(null) }
    DisposableEffect(view, context) {
        val active = CuePlayer(context, view)
        player = active
        onDispose { active.release() }
    }
    val current = player
    return remember(current, sound, haptic) { { cue -> current?.play(cue, sound, haptic) } }
}
