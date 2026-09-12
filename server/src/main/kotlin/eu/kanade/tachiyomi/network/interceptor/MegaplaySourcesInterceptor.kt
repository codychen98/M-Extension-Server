package eu.kanade.tachiyomi.network.interceptor

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val megaplayLogger = KotlinLogging.logger("MegaplaySourcesInterceptor")
private val SELECTOR_PATTERN = Regex("^[a-z0-9_-]+$")
private val jsonParser = Json { ignoreUnknownKeys = true }

/**
 * Megaplay CDN selector + enc decrypt for Anikoto / megaplay.buzz.
 *
 * (a) Copies `s` from the request Referer onto `/stream/getSources*` when missing.
 * (b) Rewrites `/stream/getSourcesNew` JSON that has `enc` and no `sources` into
 * `{"sources":{"file":...}, ...}` so the extension DTO parses.
 */
class MegaplaySourcesInterceptor(
    private val key: ByteArray = defaultKey(),
    private val iv: ByteArray = defaultIv(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = withCdnSelector(chain.request())
        val response = chain.proceed(request)
        return if (isGetSourcesNew(request.url)) rewriteEnc(response) else response
    }

    private fun rewriteEnc(response: Response): Response {
        if (!response.isSuccessful) return response
        val body = response.body ?: return response
        val mediaType = body.contentType()
        val original = body.string()
        val host = response.request.url.host
        val rewritten =
            try {
                rewriteBody(original, key, iv, host)
            } catch (_: Exception) {
                megaplayLogger.warn {
                    "[MegaplayInterceptor] enc decrypt failed host=$host len=${original.length}"
                }
                null
            }
        val out = rewritten ?: original
        return response
            .newBuilder()
            .body(out.toResponseBody(mediaType))
            .build()
    }

    companion object {
        /** Known megaplay hosts. Path must also contain `/stream/getSources`. */
        internal val ALLOWED_HOST_SUFFIXES = setOf("megaplay.buzz")

        private const val DEFAULT_KEY_STR = "i?LMTAx0Q6,:}50U"
        private const val DEFAULT_IV_STR = "W0;27ToaUpl_P%'c"

        internal fun defaultKey(): ByteArray {
            val raw = System.getProperty("mes.megaplay.key") ?: DEFAULT_KEY_STR
            return raw.toByteArray(Charsets.UTF_8).copyOf(32)
        }

        internal fun defaultIv(): ByteArray {
            val raw = System.getProperty("mes.megaplay.iv") ?: DEFAULT_IV_STR
            return raw.toByteArray(Charsets.UTF_8)
        }
    }
}

internal fun isAllowedMegaplayUrl(url: HttpUrl): Boolean {
    if (!url.encodedPath.contains("/stream/getSources")) return false
    val host = url.host.lowercase()
    return MegaplaySourcesInterceptor.ALLOWED_HOST_SUFFIXES.any {
        host == it || host.endsWith(".$it")
    }
}

internal fun isGetSourcesNew(url: HttpUrl): Boolean =
    url.encodedPath.contains("/stream/getSourcesNew") && isAllowedMegaplayUrl(url)

internal fun extractSelector(referer: String?): String? {
    if (referer.isNullOrBlank()) return null
    val url = referer.toHttpUrlOrNull() ?: return null
    val s = url.queryParameter("s") ?: return null
    if (!SELECTOR_PATTERN.matches(s)) return null
    return s
}

internal fun withCdnSelector(request: Request): Request {
    val url = request.url
    if (!isAllowedMegaplayUrl(url)) return request
    if (url.queryParameter("s") != null) return request
    val selector = extractSelector(request.header("Referer")) ?: return request
    val newUrl = url.newBuilder().addQueryParameter("s", selector).build()
    return request.newBuilder().url(newUrl).build()
}

internal fun decryptEnc(
    enc: String,
    key: ByteArray,
    iv: ByteArray,
): String {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    val decoded = Base64.getUrlDecoder().decode(enc)
    return cipher.doFinal(decoded).toString(Charsets.UTF_8)
}

/**
 * Returns rewritten JSON when [json] has `enc` and no `sources`; otherwise null.
 */
internal fun rewriteBody(
    json: String,
    key: ByteArray,
    iv: ByteArray,
    host: String = "",
): String? {
    val element =
        try {
            jsonParser.parseToJsonElement(json)
        } catch (_: Exception) {
            return null
        }
    if (element !is JsonObject) return null
    if (element.containsKey("sources")) return null
    val enc = (element["enc"] as? JsonPrimitive)?.contentOrNull ?: return null
    val plain =
        try {
            decryptEnc(enc, key, iv)
        } catch (_: Exception) {
            megaplayLogger.warn {
                "[MegaplayInterceptor] enc decrypt failed host=$host len=${enc.length}"
            }
            return null
        }
    val inner =
        try {
            jsonParser.parseToJsonElement(plain).jsonObject
        } catch (_: Exception) {
            return null
        }
    val file = inner["file"] ?: return null
    val rebuilt =
        buildJsonObject {
            element.forEach { (k, v) ->
                if (k != "enc") put(k, v)
            }
            put(
                "sources",
                buildJsonObject {
                    put("file", file)
                },
            )
        }
    return rebuilt.toString()
}
