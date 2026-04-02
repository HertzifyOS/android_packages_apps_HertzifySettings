package com.hertzify.settings.fragments.miscellaneous

import com.android.settings.R
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.regex.Pattern

class PifRepository {
    private val GOOGLE_URL = "https://developer.android.com"
    private val HERTZIFY_URL = "https://raw.githubusercontent.com/HertzifyOS/PIF/refs/heads/main/pif.json"

    sealed class PifResult {
        data class Success(val model: String, val pifData: JSONObject) : PifResult()
        data class Error(val messageRes: Int, val detail: String? = null) : PifResult()
    }

    enum class Source { GOOGLE, HERTZIFY }

    fun fetchPif(source: Source): PifResult = when (source) {
        Source.HERTZIFY -> fetchFromUrl(HERTZIFY_URL)
        Source.GOOGLE -> fetchBetaPif()
    }

    private fun fetchFromUrl(urlString: String): PifResult = try {
        val content = fetchUrl(urlString)
        val json = JSONObject(content)
        PifResult.Success(json.optString("MODEL", "GitHub Config"), json)
    } catch (e: Exception) {
        PifResult.Error(R.string.pif_fetch_error, e.message)
    }

    fun fetchBetaPif(): PifResult {
        try {
            val versionsHtml = fetchUrl("$GOOGLE_URL/about/versions")
            val versions = extractVersionNumbers(versionsHtml)
            if (versions.isEmpty()) return PifResult.Error(R.string.pif_no_valid_ota)

            for (version in versions) {
                val versionHtml = try { fetchUrl("$GOOGLE_URL/about/versions/$version") } catch (e: Exception) { continue }
                val qprPaths = extractQprPaths(versionHtml, version)

                for (qprPath in qprPaths) {
                    try {
                        val otaHtml = fetchUrl(GOOGLE_URL + qprPath)
                        val otaUrls = extractOtaUrls(otaHtml)
                        val devices = matchDevicesToOta(otaHtml, otaUrls)

                        if (devices.isNotEmpty()) {
                            val chosen = devices[Random().nextInt(devices.size)]
                            val model = chosen[0]
                            val product = chosen[1]
                            val otaUrl = chosen[2]

                            val partial = fetchPartialUrl(otaUrl, 8192)
                            val fingerprint = extractRegex(partial, "post-build=([^\\s\\n\\r]+)")
                            val securityPatch = extractRegex(partial, "security-patch-level=([^\\s\\n\\r]+)")

                            if (fingerprint != null && securityPatch != null) {
                                val pifJson = JSONObject().apply {
                                    put("MANUFACTURER", "Google")
                                    put("MODEL", model)
                                    put("PRODUCT", product)
                                    put("DEVICE", product.replace("_beta", ""))
                                    put("FINGERPRINT", fingerprint.trim())
                                    put("SECURITY_PATCH", securityPatch.trim())
                                    put("DEVICE_INITIAL_SDK_INT", "32")
                                }
                                return PifResult.Success(model, pifJson)
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            return PifResult.Error(R.string.pif_no_valid_ota)
        } catch (e: Exception) {
            return PifResult.Error(R.string.pif_fetch_error, e.message)
        }
    }

    private fun fetchUrl(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        return conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun fetchPartialUrl(urlString: String, maxBytes: Int): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        val sb = StringBuilder()
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(1024)
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buf)
                    if (read == -1) break
                    sb.append(String(buf, 0, read, StandardCharsets.ISO_8859_1))
                    total += read
                }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }
    
    private fun extractVersionNumbers(html: String): List<Int> {
        val versions = mutableListOf<Int>()
        val m = Pattern.compile("https://developer\\.android\\.com/about/versions/(\\d+)").matcher(html)
        while (m.find()) {
            m.group(1)?.toIntOrNull()?.let { if (!versions.contains(it)) versions.add(it) }
        }
        return versions.sortedDescending()
    }

    private fun extractQprPaths(html: String, version: Int): List<String> {
        val paths = mutableListOf<Pair<Int, String>>()
        val m = Pattern.compile("href=\"(/about/versions/$version/qpr(\\d+)/download-ota)\"").matcher(html)
        while (m.find()) {
            val qprNum = m.group(2)?.toInt() ?: 0
            paths.add(qprNum to m.group(1))
        }
        return paths.sortedByDescending { it.first }.map { it.second }
    }

    private fun extractOtaUrls(html: String): List<Array<String>> {
        val result = mutableListOf<Array<String>>()
        val m = Pattern.compile("href=\"(https://dl\\.google\\.com/[^\"]*ota/([^/\\s\"]+_beta)[^\"]*?)\"").matcher(html)
        while (m.find()) {
            result.add(arrayOf(m.group(1)!!, m.group(2)!!))
        }
        return result
    }

    private fun matchDevicesToOta(html: String, otaUrls: List<Array<String>>): List<Array<String>> {
        val devices = mutableListOf<Array<String>>()
        val tdPat = Pattern.compile("<td[^>]*>([^<]+)</td>")

        for (entry in otaUrls) {
            val url = entry[0]
            val product = entry[1]
            val urlIndex = html.indexOf(url)
            if (urlIndex < 0) continue

            val before = html.substring(0, urlIndex)
            val tdm = tdPat.matcher(before)
            var lastTd: String? = null
            while (tdm.find()) {
                lastTd = tdm.group(1)?.trim()
            }

            if (!lastTd.isNullOrEmpty()) {
                devices.add(arrayOf(lastTd, product, url))
            }
        }
        return devices
    }

    private fun extractRegex(text: String, regex: String): String? {
        val m = Pattern.compile(regex).matcher(text)
        return if (m.find()) m.group(1) else null
    }
}