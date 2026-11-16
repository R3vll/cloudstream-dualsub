package com.lagradost.cloudstream3.ui.player

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.media3.common.text.Cue
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.player.SubtitleData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the position of the secondary subtitle relative to the primary subtitle.
 * TOP: Secondary subtitle appears above the primary subtitle.
 * BOTTOM: Primary subtitle appears above the secondary subtitle (default, existing behavior).
 */
@Serializable
enum class SecondarySubtitlePosition {
    @JsonProperty("TOP")
    @SerialName("TOP")
    TOP,
    @JsonProperty("BOTTOM")
    @SerialName("BOTTOM")
    BOTTOM
}

data class DualSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: CharSequence
)

object DualSubtitleManager {
    var secondarySub: SubtitleData? = null
    var secondaryCues: List<DualSubtitleCue> = emptyList()
    var secondaryDelayMs: Long = 0L
    var secondaryTextSizeRatio: Float = 0.85f
    var subtitleVerticalSpacingRatio: Float = 0.6f
    
    /**
     * Position preference for secondary subtitle.
     * Default is BOTTOM to preserve existing behavior (Primary above Secondary).
     */
    var secondarySubtitlePosition: SecondarySubtitlePosition = SecondarySubtitlePosition.BOTTOM

    private fun containsRtl(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        for (i in 0 until text.length) {
            val dir = Character.getDirectionality(text[i].code)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true
            }
        }
        return false
    }

    private fun wrapRtl(text: String): String {
        if (text.isEmpty()) return text
        return if (containsRtl(text)) "‫$text‬" else text
    }

    fun clearSecondary() {
        secondarySub = null
        secondaryCues = emptyList()
    }

    fun loadSecondaryFromText(content: String, sub: SubtitleData) {
        secondarySub = sub
        secondaryCues = parseSubtitleText(content)
    }

    fun parseSubtitleText(content: String): List<DualSubtitleCue> {
        val cues = mutableListOf<DualSubtitleCue>()
        try {
            val blocks = content.split(Regex("\\r?\\n\\s*\\r?\\n"))

            for (block in blocks) {
                val lines = block.trim().lines()
                if (lines.size < 2) continue

                var timeLineIndex = -1
                for (i in lines.indices) {
                    if (lines[i].contains("-->")) {
                        timeLineIndex = i
                        break
                    }
                }
                if (timeLineIndex == -1) continue

                val timeParts = lines[timeLineIndex].split("-->")
                if (timeParts.size < 2) continue

                val startMs = parseTimestamp(timeParts[0].trim())
                val endMs = parseTimestamp(timeParts[1].trim())
                if (startMs < 0 || endMs < 0 || endMs <= startMs) continue

                val textLines = lines.subList(timeLineIndex + 1, lines.size)
                val text = textanned(textLines.joinToString("\n"))

                cues.add(DualSubtitleCue(startMs, endMs, text))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cues
    }

    private fun parseTimestamp(timeStr: String): Long {
        try {
            val cleanTime = timeStr.split(" ").first().replace(',', '.')
            val parts = cleanTime.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secParts = parts[2].split(".")
                val seconds = secParts[0].toLong()
                val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
            } else if (parts.size == 2) {
                val minutes = parts[0].toLong()
                val secParts = parts[1].split(".")
                val seconds = secParts[0].toLong()
                val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                return minutes * 60000 + seconds * 1000 + millis
            }
        } catch (e: Exception) {
            // ignore
        }
        return -1L
    }

    private fun textanned(raw: String): CharSequence {
        // Strip basic HTML tags if any (like <i>, <b>, <font>)
        return raw.replace(Regex("<[^>]*>"), "")
    }

    fun getCurrentSecondaryText(currentPosMs: Long): CharSequence? {
        if (secondaryCues.isEmpty()) return null
        val targetTime = currentPosMs - secondaryDelayMs
        val matchingCue = secondaryCues.find { targetTime in it.startMs..it.endMs }
        return matchingCue?.text
    }

    fun buildMergedCues(primaryCues: List<Cue>, secondaryText: CharSequence?): List<Cue> {
        val primaryText = primaryCues.firstOrNull()?.text?.toString()?.trim() ?: ""
        val cleanSec = secondaryText?.toString()?.trim() ?: ""

        val wrappedPrimary = wrapRtl(primaryText)
        val wrappedSecondary = wrapRtl(cleanSec)

        val hasPrimary = primaryCues.isNotEmpty() && primaryText.isNotEmpty()
        val hasSecondary = cleanSec.isNotEmpty()

        if (!hasPrimary && !hasSecondary) return primaryCues

        // Prevent duplicate language rendering if Sub1 and Sub2 have identical text
        if (hasPrimary && hasSecondary && cleanSec.equals(primaryText, ignoreCase = true)) {
            return primaryCues
        }

        val builder = SpannableStringBuilder()

        // Position based on preference: TOP = Secondary first, BOTTOM = Primary first
        when (secondarySubtitlePosition) {
            SecondarySubtitlePosition.TOP -> {
                // Secondary above Primary
                if (hasSecondary) {
                    builder.append(wrappedSecondary)
                    if (hasPrimary) {
                        val spacingSpan = SpannableString("\n").apply {
                            setSpan(
                                RelativeSizeSpan(subtitleVerticalSpacingRatio),
                                0,
                                1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        builder.append(spacingSpan)
                    }
                }

                if (hasPrimary) {
                    builder.append(wrappedPrimary)
                }
            }
            SecondarySubtitlePosition.BOTTOM -> {
                // Primary above Secondary (existing behavior)
                if (hasPrimary) {
                    builder.append(wrappedPrimary)
                    if (hasSecondary) {
                        val spacingSpan = SpannableString("\n").apply {
                            setSpan(
                                RelativeSizeSpan(subtitleVerticalSpacingRatio),
                                0,
                                1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        builder.append(spacingSpan)
                    }
                }

                if (hasSecondary) {
                    builder.append(wrappedSecondary)
                }
            }
        }

        val baseCue = if (hasPrimary) primaryCues.first() else Cue.Builder().setText("").build()
        val merged = baseCue.buildUpon()
            .setText(builder)
            .build()

        return listOf(merged)
    }
}
