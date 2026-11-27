// StreamTest | Jung1330 - Centralized Remote Domain Resolver

package com.jung1330

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import org.json.JSONObject

object DomainConfig {
    private const val DOMAINS_URL = "https://raw.githubusercontent.com/Jung1330/StreamTest/main/domains.json"
    private const val CACHE_TTL_MS = 15L * 60L * 1000L

    @Volatile
    private var cachedDomains: Map<String, String> = emptyMap()

    @Volatile
    private var lastFetchTime: Long = 0L

    private suspend fun getDomains(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (cachedDomains.isNotEmpty() && (now - lastFetchTime) < CACHE_TTL_MS) {
            return cachedDomains
        }
        return try {
            val text = app.get(DOMAINS_URL, timeout = 8).text.trim()
            if (text.startsWith("{")) {
                val obj = JSONObject(text)
                val map = HashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim().trimEnd('/')
                    if (v.startsWith("http://") || v.startsWith("https://")) {
                        map[k] = v
                    }
                }
                if (map.isNotEmpty()) {
                    cachedDomains = map
                    lastFetchTime = now
                }
            }
            cachedDomains
        } catch (_: Throwable) {
            lastFetchTime = now - CACHE_TTL_MS + 60_000L // Retry after 1 min on transient error
            cachedDomains
        }
    }

    suspend fun sync(api: MainAPI, key: String): String {
        val remote = getDomains()[key]
        if (!remote.isNullOrBlank()) {
            api.mainUrl = remote
        }
        return api.mainUrl
    }

    fun rewrite(url: String, initialBase: String, currentBase: String): String {
        val cleanInitial = initialBase.trimEnd('/')
        val cleanCurrent = currentBase.trimEnd('/')
        if (cleanInitial.equals(cleanCurrent, ignoreCase = true)) return url
        if (url.startsWith(cleanInitial, ignoreCase = true)) {
            return cleanCurrent + url.substring(cleanInitial.length)
        }
        // Also handle www. vs non-www. variant of initialBase
        val altInitial = if (cleanInitial.contains("://www.")) {
            cleanInitial.replace("://www.", "://")
        } else {
            cleanInitial.replace("://", "://www.")
        }
        if (url.startsWith(altInitial, ignoreCase = true)) {
            return cleanCurrent + url.substring(altInitial.length)
        }
        return url
    }
}
