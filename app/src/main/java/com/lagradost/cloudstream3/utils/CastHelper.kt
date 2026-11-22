package com.lagradost.cloudstream3.utils

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.images.WebImage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.MetadataHolder
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CastHelper {
    fun getMediaInfo(
        epData: ResultEpisode,
        holder: MetadataHolder,
        index: Int,
        data: JSONObject?,
        subtitles: List<SubtitleData>,
        extraTracks: List<MediaTrack> = emptyList()
    ): MediaInfo {
        val link = holder.currentLinks[index]
        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        movieMetadata.putString(
            MediaMetadata.KEY_SUBTITLE,
            if (holder.isMovie)
                "${link.name} ${Qualities.getStringByInt(link.quality)}"
            else
                (epData.name ?: "Episode ${epData.episode}") + " - ${link.name} ${Qualities.getStringByInt(link.quality)}"
        )

        holder.title?.let {
            movieMetadata.putString(MediaMetadata.KEY_TITLE, it)
        }

        val srcPoster = epData.poster ?: holder.poster
        if (srcPoster != null) {
            movieMetadata.addImage(WebImage(srcPoster.toUri()))
        }

        val tracks = castVttTracks(subtitles).map { (id, sub) ->
            MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)
                .setName(sub.name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(sub.url)
                .setContentType("text/vtt")
                .build()
        }

        val builder = MediaInfo.Builder(link.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(when(link.type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> {
                    val path = link.url.substringBefore('?').lowercase()
                    when {
                        path.endsWith(".webm") -> "video/webm"
                        path.endsWith(".mkv") -> "video/x-matroska"
                        else -> MimeTypes.VIDEO_MP4
                    }
                }
            })
            .setMetadata(movieMetadata)
            .setMediaTracks(tracks + extraTracks)
        data?.let {
            builder.setCustomData(data)
        }

        return builder.build()
    }

    fun awaitLinks(
        pending: PendingResult<RemoteMediaClient.MediaChannelResult>?,
        callback: (Boolean) -> Unit
    ) {
        if (pending == null) return
        main {
            val res = withContext(Dispatchers.IO) { pending.await() }
            val code = res.status.statusCode
            when {
                res.status.isSuccess -> Unit
                // The user stopped or replaced the cast, do not skip to the next link.
                code == CastStatusCodes.CANCELED || code == CastStatusCodes.INTERRUPTED -> Unit
                else -> {
                    callback.invoke(true)
                    println("CAST FAILED ($code) AND LOAD NEXT")
                }
            }
        }
    }

    // The dual-sub pair currently sent to the TV as one merged track, if any.
    var activeDualSubtitles: Pair<SubtitleData, SubtitleData>? = null
        private set

    fun CastSession?.startCast(
        apiName: String,
        isMovie: Boolean,
        title: String?,
        poster: String?,
        currentEpisodeIndex: Int,
        episodes: List<ResultEpisode>,
        currentLinks: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
        startIndex: Int? = null,
        startTime: Long? = null,
        preferDirect: Boolean = true,
        activeSubtitle: SubtitleData? = null,
        secondarySubtitle: SubtitleData? = null,
    ): Boolean {
        if (this == null) return false
        if (episodes.isEmpty()) return false
        if (currentEpisodeIndex >= episodes.size) return false

        if (activeSubtitle != null && secondarySubtitle != null) {
            // Dual subtitles on the TV: the default receiver renders a single
            // text track, so both languages are merged into one WebVTT served
            // from the phone. The download is async; the load happens after.
            activeDualSubtitles = activeSubtitle to secondarySubtitle
            val session = this
            main {
                val merged = withContext(Dispatchers.IO) {
                    CastDualSub.buildMergedTrack(
                        castVttTracks(subtitles).size,
                        activeSubtitle,
                        secondarySubtitle,
                    )
                }
                session.startCastInternal(
                    apiName, isMovie, title, poster, currentEpisodeIndex, episodes,
                    currentLinks, subtitles, startIndex, startTime, preferDirect,
                    activeSubtitle, merged,
                )
            }
            return true
        }

        activeDualSubtitles = null

        if (activeSubtitle != null &&
            castVttTracks(subtitles).none { it.second.url == activeSubtitle.url }
        ) {
            // The receiver only renders WebVTT; convert internet subtitles
            // (usually srt) and serve them from the phone.
            val session = this
            main {
                val converted = withContext(Dispatchers.IO) {
                    CastDualSub.buildSingleTrack(castVttTracks(subtitles).size, activeSubtitle)
                }
                session.startCastInternal(
                    apiName, isMovie, title, poster, currentEpisodeIndex, episodes,
                    currentLinks, subtitles, startIndex, startTime, preferDirect,
                    activeSubtitle, converted,
                )
            }
            return true
        }

        return startCastInternal(
            apiName, isMovie, title, poster, currentEpisodeIndex, episodes,
            currentLinks, subtitles, startIndex, startTime, preferDirect,
            activeSubtitle, null,
        )
    }

    private fun CastSession.startCastInternal(
        apiName: String,
        isMovie: Boolean,
        title: String?,
        poster: String?,
        currentEpisodeIndex: Int,
        episodes: List<ResultEpisode>,
        currentLinks: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
        startIndex: Int? = null,
        startTime: Long? = null,
        preferDirect: Boolean = true,
        activeSubtitle: SubtitleData? = null,
        mergedTrack: MergedCastTrack? = null,
    ): Boolean {
        try {
            if (episodes.isEmpty()) return false
            if (currentEpisodeIndex >= episodes.size) return false

            val epData = episodes[currentEpisodeIndex]

            // The TV receiver fetches the video URL itself and cannot send the
            // Referer/headers the phone player sends, so a link that needs them
            // plays on the phone but fails on the TV. Prefer links the receiver
            // can fetch directly; the rest stay as fallback.
            val orderedLinks = currentLinks.sortedWith(
                compareBy<ExtractorLink> { it.headers.isNotEmpty() || it.referer.isNotBlank() }
            )
            if (orderedLinks.isEmpty()) return false

            // startIndex refers to currentLinks; remap it onto the reordered list.
            val requestedLink = startIndex?.takeIf { it in currentLinks.indices }
                ?.let { currentLinks[it] }
            var index = requestedLink?.let { orderedLinks.indexOf(it) }
                ?.takeIf { it >= 0 } ?: 0
            // If the chosen link needs headers the receiver cannot send, start
            // with the best direct link instead of one that is bound to fail.
            // Only on the initial cast: retries walk the list in order and must
            // not jump back to a link that already failed.
            if (preferDirect && (orderedLinks[index].headers.isNotEmpty() || orderedLinks[index].referer.isNotBlank())) {
                val firstDirect = orderedLinks.indexOfFirst {
                    it.headers.isEmpty() && it.referer.isBlank()
                }
                if (firstDirect >= 0) index = firstDirect
            }

            // Remember which tracks are active so mid-cast switches keep the
            // other track active too. The audio default is left to the receiver.
            activeTextTrackId = mergedTrack?.id ?: activeSubtitle?.let { sel ->
                castVttTracks(subtitles).firstOrNull { it.second.url == sel.url }?.first
            }
            activeAudioTrackId = null

            val holder =
                MetadataHolder(
                    apiName,
                    isMovie,
                    title,
                    poster,
                    currentEpisodeIndex,
                    episodes,
                    orderedLinks,
                    subtitles
                )

            val mediaItem =
                getMediaInfo(
                    epData,
                    holder,
                    index,
                    JSONObject(holder.toJson()),
                    subtitles,
                    extraTracks = listOfNotNull(mergedTrack?.let { track ->
                        MediaTrack.Builder(track.id, MediaTrack.TYPE_TEXT)
                            .setName(track.name)
                            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                            .setContentId(track.url)
                            .setContentType("text/vtt")
                            .build()
                    })
                )

            val loadRequest = MediaLoadRequestData.Builder().setMediaInfo(mediaItem)
                .setCurrentTime(startTime ?: 0L)
            activeTextTrackId?.let { loadRequest.setActiveTrackIds(longArrayOf(it)) }

            awaitLinks(
                this.remoteMediaClient?.load(loadRequest.build())
            ) {
                if (orderedLinks.size > index + 1)
                    startCastInternal(
                        apiName,
                        isMovie,
                        title,
                        poster,
                        currentEpisodeIndex,
                        episodes,
                        orderedLinks,
                        subtitles,
                        index + 1,
                        startTime,
                        preferDirect = false,
                        activeSubtitle = activeSubtitle,
                        mergedTrack = mergedTrack
                    )
            }
            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    // region Mid-cast track control

    // The default receiver only renders WebVTT text tracks, so other
    // formats (srt, ass) are left out instead of risking a failed load.
    // Returns the castable subtitles with their cast track ids.
    fun castVttTracks(subtitles: List<SubtitleData>): List<Pair<Long, SubtitleData>> {
        var id = 0L
        return subtitles.filter {
            it.url.substringBefore('?').lowercase().endsWith(".vtt")
        }.map { id++ to it }
    }

    // Active cast tracks, remembered so switching one keeps the other active.
    private var activeTextTrackId: Long? = null
    private var activeAudioTrackId: Long? = null

    private fun CastSession?.applyActiveCastTracks() {
        val client = this?.remoteMediaClient ?: return
        val ids = listOfNotNull(activeTextTrackId, activeAudioTrackId)
        client.setActiveMediaTracks(ids.toLongArray())
    }

    /** Switch the subtitle shown on the TV mid-cast. Pass null to hide subtitles. */
    fun CastSession?.setCastSubtitle(subtitles: List<SubtitleData>, subtitle: SubtitleData?) {
        if (this == null) return
        activeDualSubtitles = null
        activeTextTrackId = subtitle?.let { sel ->
            castVttTracks(subtitles).firstOrNull { it.second.url == sel.url }?.first
        }
        applyActiveCastTracks()
    }

    /** Audio tracks the receiver reports for the currently loaded media. */
    fun CastSession?.getCastAudioTracks(): List<MediaTrack> {
        return this?.remoteMediaClient?.mediaInfo?.mediaTracks
            ?.filter { it.type == MediaTrack.TYPE_AUDIO } ?: emptyList()
    }

    /** Switch the audio track on the TV mid-cast. */
    fun CastSession?.setCastAudioTrack(trackId: Long) {
        if (this == null) return
        activeAudioTrackId = trackId
        applyActiveCastTracks()
    }

    /** Switch the active text track by receiver track id (used by the cast controller). */
    fun CastSession?.setCastTextTrackId(trackId: Long?) {
        if (this == null) return
        activeDualSubtitles = null
        activeTextTrackId = trackId
        applyActiveCastTracks()
    }
    // endregion
}
