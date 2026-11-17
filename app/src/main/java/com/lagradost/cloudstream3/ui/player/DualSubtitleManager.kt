package com.lagradost.cloudstream3.ui.player

import android.graphics.Color
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.setSubtitleAlignment
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
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

/**
 * Defines where the secondary subtitle is rendered.
 * STACKED: merged into the same cue as the primary subtitle (single block, previous behaviour).
 * SEPARATE: rendered as its own cue so it can be placed anywhere on screen independently.
 */
@Serializable
enum class SecondaryPositionMode {
    @JsonProperty("STACKED")
    @SerialName("STACKED")
    STACKED,
    @JsonProperty("SEPARATE")
    @SerialName("SEPARATE")
    SEPARATE
}

data class DualSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: CharSequence
)

@OptIn(UnstableApi::class)
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

    /** Whether the secondary subtitle is stacked with the primary one or placed independently. */
    var secondaryPositionMode: SecondaryPositionMode = SecondaryPositionMode.STACKED

    /** SSA alignment used when [secondaryPositionMode] is SEPARATE. */
    var secondaryAlignment: Int? = null

    /** Extra vertical nudge in percent of the view height, applied on top of [secondaryAlignment]. */
    var secondaryVerticalOffset: Float = 0f

    /** Text colour of the secondary subtitle. */
    var secondaryForegroundColor: Int = Color.WHITE

    /** Background colour drawn behind the secondary subtitle. */
    var secondaryBackgroundColor: Int = Color.TRANSPARENT

    /** Corner radius of the secondary background, in dp. */
    var secondaryBackgroundRadius: Float = 0f

    /** Opacity of the secondary subtitle, 0.0 - 1.0. */
    var secondaryOpacity: Float = 1.0f

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

    /** Copies the persisted secondary subtitle style into this runtime manager. */
    fun applyStyle(style: SaveCaptionStyle) {
        secondarySubtitlePosition = style.secondarySubtitlePosition
        secondaryPositionMode = style.secondaryPositionMode
        secondaryAlignment = style.secondaryAlignment
        secondaryVerticalOffset = style.secondaryVerticalOffset
        secondaryForegroundColor = style.secondaryForegroundColor
        secondaryBackgroundColor = style.secondaryBackgroundColor
        secondaryBackgroundRadius = style.secondaryBackgroundRadius
        secondaryOpacity = style.secondaryOpacity
        secondaryTextSizeRatio = style.secondaryTextSizeRatio
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
        // Same convention as the primary subtitle offset: a positive delay shifts the
        // subtitle earlier, i.e. the lookup time is playback position + delay.
        val targetTime = currentPosMs + secondaryDelayMs
        val matchingCue = secondaryCues.find { targetTime in it.startMs..it.endMs }
        return matchingCue?.text
    }

    /**
     * Applies the secondary subtitle styling to the range [start, end) of [text].
     */
    private fun applySecondarySpans(text: Spannable, start: Int = 0, end: Int = text.length) {
        if (end <= start) return

        text.setSpan(
            RelativeSizeSpan(secondaryTextSizeRatio.coerceIn(0.1f, 3.0f)),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        if (secondaryForegroundColor != Color.TRANSPARENT) {
            text.setSpan(
                ForegroundColorSpan(withOpacity(secondaryForegroundColor)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val background = withOpacity(secondaryBackgroundColor)
        val radius = secondaryBackgroundRadius
        if (radius > 0f && Color.alpha(background) > 0) {
            text.setSpan(
                RoundedBackgroundColorSpan(
                    background,
                    Layout.Alignment.ALIGN_CENTER,
                    2.0f + radius * 0.5f,
                    radius
                ),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Applies [secondaryOpacity] to [color]. */
    private fun withOpacity(color: Int): Int {
        if (color == Color.TRANSPARENT) return color
        val opacity = secondaryOpacity.coerceIn(0f, 1f)
        if (opacity >= 1f) return color
        val alpha = (Color.alpha(color) * opacity).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    /** Applies [applySecondarySpans] to the range starting at [start]. */
    private fun applySecondarySpansRange(text: SpannableStringBuilder, start: Int) {
        applySecondarySpans(text, start, text.length)
    }

    /**
     * Builds the secondary cue used when [secondaryPositionMode] is SEPARATE.
     * The returned cue carries its own alignment/position so it can sit anywhere on screen.
     */
    fun buildSeparateSecondaryCue(secondaryText: CharSequence?): Cue? {
        val clean = secondaryText?.toString()?.trim().orEmpty()
        if (clean.isEmpty()) return null

        val spannable = SpannableString(wrapRtl(clean))
        applySecondarySpans(spannable)

        val builder = Cue.Builder().setText(spannable)
        builder.setSubtitleAlignment(secondaryAlignment ?: CustomDecoder.SSA_ALIGNMENT_BOTTOM_CENTER)

        // Fine tuning on top of the coarse alignment.
        if (secondaryVerticalOffset != 0f) {
            val baseLine = if (builder.getLine() != Cue.DIMEN_UNSET) builder.getLine() else 0.95f
            builder.setLine(
                (baseLine + secondaryVerticalOffset / 100f).coerceIn(-0.5f, 1.5f),
                Cue.LINE_TYPE_FRACTION
            )
        }

        return builder.build()
    }

    /**
     * Combines the primary cues and the secondary subtitle into the final cue list.
     */
    fun buildFinalCues(primaryCues: List<Cue>, secondaryText: CharSequence?): List<Cue> {
        if (secondaryPositionMode == SecondaryPositionMode.SEPARATE) {
            val secondaryCue = buildSeparateSecondaryCue(secondaryText) ?: return primaryCues
            val primaryText = primaryCues.firstOrNull()?.text?.toString()?.trim().orEmpty()
            // Prevent duplicate language rendering if Sub1 and Sub2 have identical text
            if (primaryText.isNotEmpty() &&
                secondaryCue.text?.toString()?.trim().equals(primaryText, ignoreCase = true)
            ) {
                return primaryCues
            }
            return primaryCues + secondaryCue
        }

        return buildMergedCues(primaryCues, secondaryText)
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
                    val start = builder.length
                    builder.append(wrappedSecondary)
                    applySecondarySpansRange(builder, start)
                    if (hasPrimary) {
                        builder.append(styledNewLine())
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
                        builder.append(styledNewLine())
                    }
                }

                if (hasSecondary) {
                    val start = builder.length
                    builder.append(wrappedSecondary)
                    applySecondarySpansRange(builder, start)
                }
            }
        }

        val baseCue = if (hasPrimary) primaryCues.first() else Cue.Builder().setText("").build()
        val merged = baseCue.buildUpon()
            .setText(builder)
            .build()

        return listOf(merged)
    }

    /** Newline separating the two subtitles, sized by [subtitleVerticalSpacingRatio]. */
    private fun styledNewLine(): CharSequence {
        return SpannableString("\n").apply {
            setSpan(
                RelativeSizeSpan(subtitleVerticalSpacingRatio),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
