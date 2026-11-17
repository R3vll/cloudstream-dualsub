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
        subtitles: List<SubtitleData>
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

        var subIndex = 0
        val tracks = subtitles.map {
            MediaTrack.Builder(subIndex++.toLong(), MediaTrack.TYPE_TEXT)
                .setName(it.name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(it.url)
                .build()
        }

        val builder = MediaInfo.Builder(link.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(when(link.type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.VIDEO_MP4
            })
            .setMetadata(movieMetadata)
            .setMediaTracks(tracks)
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
            when (res.status.statusCode) {
                CastStatusCodes.FAILED -> {
                    callback.invoke(true)
                    println("FAILED AND LOAD NEXT")
                }
                else -> Unit //IDK DO SMTH HERE
            }
        }
    }

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
    ): Boolean {
        try {
            if (this == null) return false
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
                getMediaInfo(epData, holder, index, JSONObject(holder.toJson()), subtitles)

            awaitLinks(
                this.remoteMediaClient?.load(
                    MediaLoadRequestData.Builder().setMediaInfo(mediaItem)
                        .setCurrentTime(startTime ?: 0L).build()
                )
            ) {
                if (orderedLinks.size > index + 1)
                    startCast(
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
                        preferDirect = false
                    )
            }
            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
}