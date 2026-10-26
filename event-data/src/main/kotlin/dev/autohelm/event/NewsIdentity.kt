package dev.autohelm.event

import java.net.URI
import java.security.MessageDigest

/** The article URL identifies one news event across RSS feeds and GDELT queries. */
object NewsIdentity {
    fun canonicalUrl(value: String): String? = runCatching {
        val uri = URI.create(value.trim())
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
        val kept = uri.rawQuery?.split('&')?.filterNot { part ->
            val key = part.substringBefore('=').lowercase()
            key.startsWith("utm_") || key in setOf("fbclid", "gclid")
        }?.sorted()?.joinToString("&")?.ifBlank { null }
        "${uri.scheme.lowercase()}://${uri.rawAuthority.lowercase()}${uri.rawPath.ifBlank { "/" }}${kept?.let { "?$it" } ?: ""}"
    }.getOrNull()

    fun id(url: String): String = "NEWS:${hash(url)}"

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
