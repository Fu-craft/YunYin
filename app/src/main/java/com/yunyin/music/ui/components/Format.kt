package com.yunyin.music.ui.components

import kotlin.math.ln
import kotlin.math.pow

/** iOS-style compact counts: 1234 → "1234", 12345 → "1.2万". */
fun formatCount(value: Long): String = when {
    value < 10_000 -> "$value"
    value < 100_000_000 -> {
        val wan = value / 10_000.0
        "${trim(wan)}万"
    }
    else -> {
        val yi = value / 100_000_000.0
        "${trim(yi)}亿"
    }
}

/** `mm:ss`, used for both duration columns and the queue. */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun trim(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/** Natural log used by the artwork-size helper; kept here to avoid a util sprawl. */
internal fun logScale(size: Int): Double = ln(size.toDouble()).pow(1.0)
