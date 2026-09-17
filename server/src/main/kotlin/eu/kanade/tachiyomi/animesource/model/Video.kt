package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlin.jvm.Transient
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import rx.subjects.Subject

data class Track(
    val url: String,
    val lang: String,
)

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

/**
 * Bridge shim compatible with Aniyomi ext-lib 15 (legacy) and 16/17 (videoTitle primary).
 * Keeps [status] as [Int] and [ProgressListener] fields used by MihonInvoker / downloads.
 */
open class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
) : ProgressListener {
    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String
        get() = videoTitle

    val url: String
        get() = videoPageUrl

    private var videoPageUrl: String = ""

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoUrl = videoUrl ?: "",
        videoTitle = quality,
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        this.videoPageUrl = url
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    constructor(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        internalData: String = "",
        initialized: Boolean = false,
    ) : this(
        videoUrl,
        videoTitle,
        resolution,
        bitrate,
        headers,
        preferred,
        subtitleTracks,
        audioTracks,
        timestamps,
        mpvArgs,
        ffmpegStreamArgs,
        ffmpegVideoArgs,
        internalData,
        initialized,
        JsonObject(emptyMap()),
    )

    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
        memo: JsonObject = this.memo,
    ): Video =
        Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            mpvArgs = mpvArgs,
            ffmpegStreamArgs = ffmpegStreamArgs,
            ffmpegVideoArgs = ffmpegVideoArgs,
            internalData = internalData,
            initialized = initialized,
            memo = memo,
        ).also { it.videoPageUrl = this.videoPageUrl }

    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
    ): Video =
        Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            mpvArgs = mpvArgs,
            ffmpegStreamArgs = ffmpegStreamArgs,
            ffmpegVideoArgs = ffmpegVideoArgs,
            internalData = internalData,
            initialized = initialized,
            memo = JsonObject(emptyMap()),
        ).also { it.videoPageUrl = this.videoPageUrl }

    fun usesHttpServer(): Boolean {
        if (localUrl.find(videoUrl) != null) {
            return true
        }
        if (audioTracks.any { localUrl.find(it.url) != null }) {
            return true
        }
        if (subtitleTracks.any { localUrl.find(it.url) != null }) {
            return true
        }
        return false
    }

    fun copyHttpServer(port: Int): Video {
        val newHost = "http://localhost:$port"
        return copy(
            videoUrl = localUrl.replace(videoUrl, newHost),
            subtitleTracks =
                subtitleTracks.map {
                    it.copy(url = localUrl.replace(it.url, newHost))
                },
            audioTracks =
                audioTracks.map {
                    it.copy(url = localUrl.replace(it.url, newHost))
                },
        )
    }

    @Transient
    @Volatile
    var status: Int = 0
        set(value) {
            field = value
            statusSubject?.onNext(value)
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var progress: Int = 0
        set(value) {
            progressSubject?.onNext(value)
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var totalBytesDownloaded: Long = 0L

    @Transient
    @Volatile
    var totalContentLength: Long = 0L

    @Transient
    @Volatile
    var bytesDownloaded: Long = 0L
        set(value) {
            totalBytesDownloaded +=
                if (value < field) {
                    value
                } else {
                    value - field
                }
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    private var statusSubject: Subject<Int, Int>? = null

    @Transient
    private var progressSubject: Subject<Int, Int>? = null

    @Transient
    private var statusCallback: ((Video) -> Unit)? = null

    override fun update(
        bytesRead: Long,
        contentLength: Long,
        done: Boolean,
    ) {
        bytesDownloaded = bytesRead
        if (contentLength > totalContentLength) {
            totalContentLength = contentLength
        }
        val newProgress =
            if (totalContentLength > 0) {
                (100 * totalBytesDownloaded / totalContentLength).toInt()
            } else {
                -1
            }
        if (progress != newProgress) progress = newProgress
    }

    fun setStatusSubject(subject: Subject<Int, Int>?) {
        this.statusSubject = subject
    }

    fun setProgressSubject(subject: Subject<Int, Int>?) {
        this.progressSubject = subject
    }

    fun setStatusCallback(f: ((Video) -> Unit)?) {
        statusCallback = f
    }

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }

    companion object {
        const val QUEUE = 0
        const val LOAD_VIDEO = 1
        const val DOWNLOAD_IMAGE = 2
        const val READY = 3
        const val ERROR = 4

        const val MPV_ARGS_TAG = "ANIYOMI_MPV_ARGS"

        private val localUrl = Regex("""http:\/\/localhost:1(?!\d)""")
    }
}
