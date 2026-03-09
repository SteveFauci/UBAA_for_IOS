package cn.edu.ubaa.auth

import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import java.util.concurrent.ConcurrentHashMap

class InMemorySessionStore : SessionPersistence {
  private val sessions = ConcurrentHashMap<String, SessionPersistence.SessionRecord>()

  override suspend fun saveSession(
    username: String,
    userData: cn.edu.ubaa.model.dto.UserData,
    authenticatedAt: java.time.Instant,
    lastActivity: java.time.Instant,
  ) {
    sessions[username] = SessionPersistence.SessionRecord(userData, authenticatedAt, lastActivity)
  }

  override suspend fun updateLastActivity(username: String, lastActivity: java.time.Instant) {
    val current = sessions[username] ?: return
    sessions[username] = current.copy(lastActivity = lastActivity)
  }

  override suspend fun loadSession(username: String): SessionPersistence.SessionRecord? = sessions[username]

  override suspend fun deleteSession(username: String) {
    sessions.remove(username)
  }

  override fun close() {
    sessions.clear()
  }
}

class InMemoryCookieStorageFactory : ManagedCookieStorageFactory {
  private val storages = ConcurrentHashMap<String, InMemoryCookieStorage>()

  override fun create(subject: String): ManagedCookieStorage {
    return storages.computeIfAbsent(subject) { InMemoryCookieStorage() }
  }

  override suspend fun clearSubject(subject: String) {
    storages.remove(subject)?.clear()
  }

  private fun storage(subject: String): InMemoryCookieStorage {
    return storages.computeIfAbsent(subject) { InMemoryCookieStorage() }
  }

  private inner class InMemoryCookieStorage : ManagedCookieStorage {
    private val cookies = ConcurrentHashMap<String, StoredCookie>()

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
      val domain = (cookie.domain ?: requestUrl.host).lowercase()
      val path = cookie.path ?: requestUrl.encodedPath.ifBlank { "/" }
      val key = "$domain|$path|${cookie.name}"
      cookies[key] = StoredCookie(cookie.copy(domain = domain, path = path), System.currentTimeMillis())
    }

    override suspend fun get(requestUrl: Url): List<Cookie> {
      val now = System.currentTimeMillis()
      return cookies.values.mapNotNull { stored ->
        val cookie = stored.cookie
        val expiresAt = cookie.expires?.timestamp
        val maxAge = cookie.maxAge ?: -1
        val expired =
          (expiresAt != null && expiresAt <= now) ||
            (maxAge >= 0 && now >= (stored.createdAt + maxAge * 1000L))
        if (expired) {
          cookies.remove(key(cookie))
          return@mapNotNull null
        }
        if (!domainMatches(requestUrl.host, cookie.domain ?: requestUrl.host)) return@mapNotNull null
        if (!pathMatches(requestUrl.encodedPath, cookie.path ?: "/")) return@mapNotNull null
        if (cookie.secure && !requestUrl.protocol.name.equals("https", true)) return@mapNotNull null
        cookie.copy(expires = expiresAt?.let { GMTDate(it) })
      }
    }

    override suspend fun clear() {
      cookies.clear()
    }

    override suspend fun migrateTo(newSubject: String) {
      val target = storage(newSubject)
      target.cookies.putAll(cookies)
      cookies.clear()
    }

    override fun close() {}

    private fun key(cookie: Cookie): String {
      return "${cookie.domain}|${cookie.path}|${cookie.name}"
    }
  }

  private data class StoredCookie(val cookie: Cookie, val createdAt: Long)

  private fun domainMatches(host: String, domain: String): Boolean {
    val cleanDomain = domain.trimStart('.').lowercase()
    val cleanHost = host.lowercase()
    return cleanHost == cleanDomain || cleanHost.endsWith(".$cleanDomain")
  }

  private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
    val req = requestPath.ifBlank { "/" }
    val norm = if (cookiePath.endsWith("/")) cookiePath else "$cookiePath/"
    return req.startsWith(norm.removeSuffix("/"))
  }
}
