package eu.kanade.tachiyomi.network.interceptor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MegaplaySourcesInterceptorTest {
    private val key = MegaplaySourcesInterceptor.defaultKey()
    private val iv = MegaplaySourcesInterceptor.defaultIv()

    @Test
    fun `extractSelector reads sanitized s from referer`() {
        assertEquals(
            "bcdn",
            extractSelector("https://megaplay.buzz/stream/s-2/609644/sub?s=bcdn"),
        )
        assertNull(extractSelector(null))
        assertNull(extractSelector("https://megaplay.buzz/stream/s-2/609644/sub"))
        assertNull(extractSelector("https://megaplay.buzz/stream/s-2/609644/sub?s=bad!"))
        assertNull(extractSelector("not a url"))
    }

    @Test
    fun `withCdnSelector adds s from Referer when missing`() {
        val request =
            Request
                .Builder()
                .url("https://megaplay.buzz/stream/getSourcesNew?id=1&type=sub")
                .header("Referer", "https://megaplay.buzz/stream/s-2/609644/sub?s=bcdn")
                .build()

        val out = withCdnSelector(request)
        assertEquals("bcdn", out.url.queryParameter("s"))
        assertEquals("1", out.url.queryParameter("id"))
    }

    @Test
    fun `withCdnSelector leaves URL alone when s already present`() {
        val request =
            Request
                .Builder()
                .url("https://megaplay.buzz/stream/getSources?id=1&type=sub&s=existing")
                .header("Referer", "https://megaplay.buzz/stream/s-2/609644/sub?s=bcdn")
                .build()

        val out = withCdnSelector(request)
        assertEquals("existing", out.url.queryParameter("s"))
        assertEquals(request.url.toString(), out.url.toString())
    }

    @Test
    fun `withCdnSelector leaves URL alone when Referer lacks s`() {
        val request =
            Request
                .Builder()
                .url("https://megaplay.buzz/stream/getSourcesNew?id=1&type=sub")
                .header("Referer", "https://megaplay.buzz/stream/s-2/609644/sub")
                .build()

        val out = withCdnSelector(request)
        assertNull(out.url.queryParameter("s"))
        assertEquals(request.url.toString(), out.url.toString())
    }

    @Test
    fun `withCdnSelector ignores non-getSources paths`() {
        val request =
            Request
                .Builder()
                .url("https://megaplay.buzz/stream/s-2/609644/sub")
                .header("Referer", "https://megaplay.buzz/stream/s-2/609644/sub?s=bcdn")
                .build()

        val out = withCdnSelector(request)
        assertEquals(request.url.toString(), out.url.toString())
    }

    @Test
    fun `withCdnSelector ignores non-megaplay hosts`() {
        val request =
            Request
                .Builder()
                .url("https://evil.example/stream/getSourcesNew?id=1&type=sub")
                .header("Referer", "https://evil.example/stream/s-2/1/sub?s=bcdn")
                .build()

        val out = withCdnSelector(request)
        assertNull(out.url.queryParameter("s"))
    }

    @Test
    fun `decryptEnc round-trips a known plaintext vector`() {
        val plain = """{"file":"https://example.test/master.m3u8"}"""
        val enc = encryptEnc(plain, key, iv)
        assertEquals(plain, decryptEnc(enc, key, iv))
    }

    @Test
    fun `rewriteBody converts enc response into sources file`() {
        val fileUrl = "https://ncdn.example/master.m3u8"
        val enc = encryptEnc("""{"file":"$fileUrl"}""", key, iv)
        val input =
            """{"t":1,"server":4,"intro":{"start":0,"end":1},"outro":{"start":2,"end":3},"tracks":[],"enc":"$enc"}"""

        val out = assertNotNull(rewriteBody(input, key, iv))
        val obj = Json.parseToJsonElement(out).jsonObject

        assertEquals(fileUrl, obj["sources"]!!.jsonObject["file"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("enc"))
        assertEquals(1, obj["t"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, obj["server"]!!.jsonPrimitive.content.toInt())
        assertTrue(obj.containsKey("intro"))
        assertTrue(obj.containsKey("outro"))
        assertTrue(obj.containsKey("tracks"))
    }

    @Test
    fun `rewriteBody returns null when sources already present or enc missing`() {
        assertNull(
            rewriteBody(
                """{"sources":{"file":"https://a.test/x.m3u8"},"enc":"ignored"}""",
                key,
                iv,
            ),
        )
        assertNull(rewriteBody("""{"t":1,"server":4}""", key, iv))
    }

    @Test
    fun `rewriteBody returns null for malformed enc`() {
        assertNull(
            rewriteBody(
                """{"t":1,"enc":"not-valid-ciphertext!!!"}""",
                key,
                iv,
            ),
        )
    }

    @Test
    fun `interceptor end-to-end adds s and rewrites enc body`() {
        val fileUrl = "https://ncdn.example/master.m3u8"
        val enc = encryptEnc("""{"file":"$fileUrl"}""", key, iv)
        val encBody = """{"t":1,"server":4,"intro":{"start":0,"end":1},"enc":"$enc"}"""
        var downstreamUrl: String? = null

        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(MegaplaySourcesInterceptor(key, iv))
                .addInterceptor { chain ->
                    downstreamUrl = chain.request().url.toString()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(encBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()

        val response =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("https://megaplay.buzz/stream/getSourcesNew?id=1&type=sub")
                        .header("Referer", "https://megaplay.buzz/stream/s-2/609644/sub?s=bcdn")
                        .build(),
                ).execute()

        assertTrue(downstreamUrl!!.contains("s=bcdn"))
        val body = response.body!!.string()
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(fileUrl, obj["sources"]!!.jsonObject["file"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("enc"))
    }

    @Test
    fun `interceptor passes non-2xx through unchanged`() {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(MegaplaySourcesInterceptor(key, iv))
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("""{"enc":"whatever"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()

        val response =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("https://megaplay.buzz/stream/getSourcesNew?id=1&type=sub")
                        .header("Referer", "https://megaplay.buzz/stream/s-2/609644/sub?s=bcdn")
                        .build(),
                ).execute()

        assertEquals(403, response.code)
        assertEquals("""{"enc":"whatever"}""", response.body!!.string())
    }

    private fun encryptEnc(
        plain: String,
        key: ByteArray,
        iv: ByteArray,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    }
}
