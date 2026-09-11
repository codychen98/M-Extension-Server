package eu.kanade.tachiyomi.animesource.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoCompatTest {
    @Test
    fun `getVideoTitle returns legacy quality`() {
        val video = Video(url = "https://example.com/page", quality = "1080p", videoUrl = "https://cdn/stream.m3u8")

        val getVideoTitle = Video::class.java.getMethod("getVideoTitle")
        assertEquals("1080p", getVideoTitle.invoke(video))
        assertEquals("1080p", video.quality)
        assertEquals("https://example.com/page", video.url)
        assertEquals("https://cdn/stream.m3u8", video.videoUrl)
    }

    @Test
    fun `legacy null videoUrl maps to empty string`() {
        val video = Video(url = "https://example.com/page", quality = "720p", videoUrl = null)

        assertEquals("", video.videoUrl)
        assertEquals("720p", video.videoTitle)
    }

    @Test
    fun `primary 15-param and ext-lib-16 14-param constructors exist`() {
        val ctors =
            Video::class.java.declaredConstructors.filter { ctor ->
                ctor.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") }
            }

        assertTrue(ctors.any { it.parameterCount == 15 }, "expected Aniyomi primary 15-param ctor")
        assertTrue(ctors.any { it.parameterCount == 14 }, "expected ext-lib-16 HIDDEN 14-param ctor")
    }

    @Test
    fun `copy with 15 params exists`() {
        val copyMethods =
            Video::class.java.declaredMethods.filter { method ->
                method.name == "copy" && !method.isSynthetic
            }

        assertTrue(copyMethods.any { it.parameterCount == 15 }, "expected 15-param copy")
        assertTrue(copyMethods.any { it.parameterCount == 14 }, "expected ext-lib-16 HIDDEN 14-param copy")
    }

    @Test
    fun `jackson still emits quality url and videoUrl`() {
        val video = Video(url = "https://example.com/page", quality = "1080p", videoUrl = "https://cdn/stream.m3u8")
        val json = jacksonObjectMapper().writeValueAsString(video)

        assertTrue(json.contains("\"quality\""), json)
        assertTrue(json.contains("\"url\""), json)
        assertTrue(json.contains("\"videoUrl\""), json)
        assertTrue(json.contains("1080p"), json)
        assertTrue(json.contains("https://cdn/stream.m3u8"), json)
    }
}
