package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * Remembers which video source (mirror/server) was last used for a given episode, so that
 * reopening that episode restores the same source instead of falling back to whatever link
 * happens to sort first.
 *
 * Only stable identifiers are persisted - provider name, server name and quality. Streaming
 * URLs are deliberately never stored because they expire between sessions.
 */
object LastSourceStore {
    private const val FOLDER = "last_source"
    private const val SEPARATOR = "\u001f"

    /** Stable, persistable identity of a video link. */
    data class SourceId(
        val source: String,
        val name: String,
        val quality: Int,
    ) {
        fun encode(): String = "$source$SEPARATOR$name$SEPARATOR$quality"

        companion object {
            fun decode(raw: String?): SourceId? {
                if (raw.isNullOrBlank()) return null
                val parts = raw.split(SEPARATOR)
                if (parts.size != 3) return null
                val quality = parts[2].toIntOrNull() ?: return null
                if (parts[0].isBlank() && parts[1].isBlank()) return null
                return SourceId(parts[0], parts[1], quality)
            }
        }
    }

    /** Extracts the persistent identity of [link], or null if it has nothing usable. */
    fun idOf(link: VideoLink): SourceId? {
        val extractorLink = link.first
        if (extractorLink != null) {
            if (extractorLink.name.isBlank()) return null
            return SourceId(
                extractorLink.source,
                extractorLink.name,
                extractorLink.quality,
            )
        }

        val uri = link.second ?: return null
        if (uri.name.isBlank()) return null
        return SourceId("", uri.name, 0)
    }

    /**
     * Storage key for [contentId]. Sanitized (and salted with a hash) so the id can never
     * inject characters that would break the preferences file.
     */
    private fun prefKey(contentId: String): String {
        val safe = contentId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${DataStoreHelper.currentAccount}/$FOLDER/$safe-${contentId.hashCode()}"
    }

    /** Stores the source used for [contentId]. */
    fun save(contentId: String?, link: VideoLink) {
        val id = contentId ?: return
        val sourceId = idOf(link) ?: return
        setKey(prefKey(id), sourceId.encode())
    }

    /** Returns the source remembered for [contentId], or null if there is none. */
    fun restore(contentId: String?): SourceId? {
        val id = contentId ?: return null
        return SourceId.decode(getKey<String>(prefKey(id)))
    }

    /**
     * True when [link] is the same source as [savedId].
     *
     * Quality is intentionally ignored: providers sometimes report a slightly different
     * quality for the same server between scrapes, and that must not defeat the restore.
     */
    fun matches(link: VideoLink, savedId: SourceId): Boolean {
        val current = idOf(link) ?: return false
        return current.source == savedId.source && current.name == savedId.name
    }

    /** Like [matches], but also requires the quality to be unchanged. */
    fun matchesExactly(link: VideoLink, savedId: SourceId): Boolean =
        idOf(link) == savedId
}
