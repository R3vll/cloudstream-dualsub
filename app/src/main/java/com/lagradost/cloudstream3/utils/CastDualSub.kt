package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.DualSubtitleCue
import com.lagradost.cloudstream3.ui.player.DualSubtitleManager
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Dual subtitles on the Chromecast.
 *
 * The default CAF receiver renders exactly one text track, so two languages on
 * the TV are sent as a single merged WebVTT: the phone downloads both subtitle
 * files, interleaves their cues by timestamp and serves the result over a tiny
 * local HTTP server. The receiver fetches it from the phone like any other
 * text track URL; CORS headers are required by the receiver.
 */

/** A merged dual-language text track ready to attach to cast MediaInfo. */
data class MergedCastTrack(
    val id: Long,
    val name: String,
    val url: String,
)

object CastSubServer {
    @Volatile
    private var vttBytes: ByteArray = ByteArray(0)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun ensureStarted() {
        if (socket != null) return
        try {
            val server = ServerSocket(0)
            socket = server
            port = server.localPort
            thread(isDaemon = true, name = "CastSubServer") {
                while (true) {
                    try {
                        val client = server.accept()
                        thread(isDaemon = true) { serve(client) }
                    } catch (_: Exception) {
                        // Socket closed or transient accept failure; keep serving.
                    }
                }
            }
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun setContent(vtt: String) {
        vttBytes = vtt.toByteArray(Charsets.UTF_8)
    }

    /** URL the cast receiver uses to fetch the merged subtitle, or null off-LAN. */
    fun url(): String? {
        val ip = localIpAddress() ?: return null
        if (port == 0) return null
        return "http://$ip:$port/dual.vtt"
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5000
            client.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Consume the remaining request headers.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val method = requestLine.substringBefore(' ').uppercase()
                val out = sock.getOutputStream()
                val cors = "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n"
                if (method == "OPTIONS") {
                    out.write("HTTP/1.1 204 No Content\r\n${cors}Content-Length: 0\r\n\r\n".toByteArray())
                } else {
                    val body = vttBytes
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/vtt; charset=utf-8\r\n" +
                                cors +
                                "Cache-Control: no-cache\r\n" +
                                "Content-Length: ${body.size}\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                }
                out.flush()
            }
        } catch (_: Exception) {
            // Best-effort serving; the receiver retries or shows no subtitles.
        }
    }

    private fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}

object CastDualSub {
    /** Builds the merged dual-language track, or null when it cannot be made. */
    suspend fun buildMergedTrack(
        existingTrackCount: Int,
        primary: SubtitleData,
        secondary: SubtitleData,
    ): MergedCastTrack? {
        // Embedded subtitles have no fetchable URL; only downloaded/online ones do.
        if (primary.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO ||
            secondary.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO
        ) return null

        val primaryText = download(primary) ?: return null
        val secondaryText = download(secondary) ?: return null
        val vtt = mergeToVtt(
            DualSubtitleManager.parseSubtitleText(primaryText),
            DualSubtitleManager.parseSubtitleText(secondaryText),
        ) ?: return null

        CastSubServer.ensureStarted()
        val url = CastSubServer.url() ?: return null
        CastSubServer.setContent(vtt)
        return MergedCastTrack(
            id = existingTrackCount.toLong(),
            name = "${primary.originalName} + ${secondary.originalName}",
            url = url,
        )
    }

    /**
     * Converts one subtitle of any format into a WebVTT track the receiver can
     * render. Internet subtitles are usually srt, which the receiver rejects.
     */
    suspend fun buildSingleTrack(
        existingTrackCount: Int,
        sub: SubtitleData,
    ): MergedCastTrack? {
        if (sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) return null
        val text = download(sub) ?: return null
        val vtt = mergeToVtt(DualSubtitleManager.parseSubtitleText(text), emptyList())
            ?: return null

        CastSubServer.ensureStarted()
        val url = CastSubServer.url() ?: return null
        CastSubServer.setContent(vtt)
        return MergedCastTrack(
            id = existingTrackCount.toLong(),
            name = sub.originalName,
            url = url,
        )
    }

    private suspend fun download(sub: SubtitleData): String? {
        return try {
            app.get(sub.getFixedUrl(), headers = sub.headers).text
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    /**
     * Merges two parsed cue lists into a single WebVTT document. Each output cue
     * covers an interval where the active cue combination is constant; the
     * secondary language sits above the primary one, mirroring the merged cue
     * the phone player renders locally.
     */
    fun mergeToVtt(
        primary: List<DualSubtitleCue>,
        secondary: List<DualSubtitleCue>,
    ): String? {
        if (primary.isEmpty() && secondary.isEmpty()) return null

        val points = sortedSetOf<Long>()
        primary.forEach { points += it.startMs; points += it.endMs }
        secondary.forEach { points += it.startMs; points += it.endMs }
        val bounds = points.toList()

        fun covering(list: List<DualSubtitleCue>, t0: Long, t1: Long) =
            list.firstOrNull { it.startMs <= t0 && it.endMs >= t1 }

        val out = StringBuilder("WEBVTT\n\n")
        for (i in 0 until bounds.size - 1) {
            val t0 = bounds[i]
            val t1 = bounds[i + 1]
            if (t1 <= t0) continue
            val primaryCue = covering(primary, t0, t1)
            val secondaryCue = covering(secondary, t0, t1)
            if (primaryCue == null && secondaryCue == null) continue
            out.append(formatVttTime(t0)).append(" --> ").append(formatVttTime(t1)).append('\n')
            if (secondaryCue != null) out.append(secondaryCue.text).append('\n')
            if (primaryCue != null) out.append(primaryCue.text).append('\n')
            out.append('\n')
        }
        return out.toString()
    }

    private fun formatVttTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        val millis = ms % 1_000
        return String.format("%02d:%02d:%02d.%03d", h, m, s, millis)
    }
}
