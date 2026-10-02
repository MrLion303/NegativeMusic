package com.negativestudios.negativemusic

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * Small wrapper around Android's device audio Equalizer effect.
 * The effect is attached to ExoPlayer's audio session, so changes affect playback.
 */
object PlaybackAudioEffects {
    private var equalizer: Equalizer? = null

    @Synchronized
    fun attach(context: Context, audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        runCatching {
            val prefs = context.getSharedPreferences("negative_music", Context.MODE_PRIVATE)
            val effect = Equalizer(0, audioSessionId)
            equalizer = effect
            val count = effect.numberOfBands.toInt().coerceAtMost(5)
            val values = (0 until 5).map { prefs.getInt("eqBand$it", 0) }
            applyBands(values)
            effect.enabled = prefs.getBoolean("eq", false)
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
    }

    @Synchronized
    fun applyBands(valuesDb: List<Int>) {
        val effect = equalizer ?: return
        runCatching {
            val range = effect.bandLevelRange
            val maxBands = effect.numberOfBands.toInt().coerceAtMost(valuesDb.size)
            for (i in 0 until maxBands) {
                val millibels = (valuesDb[i] * 100).coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                effect.setBandLevel(i.toShort(), millibels)
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
