package cn.edu.ubaa.auth

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 Redis 实现的持久化 Cookie 存储。 为每个用户提供隔离的 Cookie 空间，确保重启后用户在上游系统（如 SSO）的登录状态依然有效。
 *
 * @param redisUri Redis 连接 URI，如 "redis://localhost:6379"。
 * @param username 关联的用户名（隔离标识）。
 */
class RedisCookieStorage(private val redisUri: String, private val username: String) : ManagedCookieStorage {
  private val mutex = Mutex()
  private val client: RedisClient by lazy { RedisClient.create(redisUri) }
  private val connection: StatefulRedisConnection<String, String> by lazy { client.connect() }
  private val commands: RedisCommands<String, String> by lazy { connection.sync() }
  private val key = storageKey(username)
  private val keyTtlSeconds = 1800L

  override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
    val domain = (cookie.domain ?: requestUrl.host).lowercase()
    val path = cookie.path ?: requestUrl.encodedPath.ifBlank { "/" }
    val field = cookieField(cookie.name, domain, path)
    val expiresAt = cookie.expires?.timestamp
    val maxAge = cookie.maxAge ?: -1
    val createdAt = System.currentTimeMillis()
    val value =
      serializeCookie(
        name = cookie.name,
        value = cookie.value,
        domain = domain,
        path = path,
        expiresAt = expiresAt,
        secure = cookie.secure,
        httpOnly = cookie.httpOnly,
        maxAge = maxAge,
        createdAt = createdAt,
      )

    mutex.withLock {
      redis {
        commands.hset(key, field, value)
        commands.expire(key, computeKeyTtlSeconds(expiresAt, maxAge, createdAt))
      }
    }
  }

  override suspend fun get(requestUrl: Url): List<Cookie> {
    val now = System.currentTimeMillis()
    return mutex.withLock {
      val cookieMap = redis { commands.hgetall(key) }.orEmpty()
      if (cookieMap.isEmpty()) return@withLock emptyList()

      val results = mutableListOf<Cookie>()
      val expiredFields = mutableListOf<String>()

      for ((field, encodedCookie) in cookieMap) {
        val record = decodeCookie(encodedCookie) ?: run {
          expiredFields.add(field)
          continue
        }
        if (isExpired(now, record.expiresAt, record.maxAge, record.createdAt)) {
          expiredFields.add(field)
          continue
        }
        if (!domainMatches(requestUrl.host, record.domain)) continue
        if (!pathMatches(requestUrl.encodedPath, record.path)) continue
        if (record.secure && !isHttps(requestUrl)) continue

        results.add(
          Cookie(
            name = record.name,
            value = record.value,
            domain = record.domain,
            path = record.path,
            expires = record.expiresAt?.let { GMTDate(it) },
            secure = record.secure,
            httpOnly = record.httpOnly,
            maxAge = record.maxAge,
            encoding = CookieEncoding.RAW,
          )
        )
      }

      if (expiredFields.isNotEmpty()) {
        redis {
          commands.hdel(key, *expiredFields.toTypedArray())
          if ((commands.hlen(key) ?: 0L) == 0L) {
            commands.del(key)
          }
        }
      }

      results
    }
  }

  override fun close() {
    try {
      runCatching { connection.close() }
      client.shutdown()
    } catch (_: Exception) {
    }
  }

  override suspend fun clear() {
    mutex.withLock { redis { commands.del(key) } }
  }

  override suspend fun migrateTo(newUsername: String) {
    val targetKey = storageKey(newUsername)
    mutex.withLock {
      redis {
        val cookieMap = commands.hgetall(key)
        if (cookieMap.isEmpty()) {
          commands.del(key)
          return@redis
        }
        commands.del(targetKey)
        commands.hset(targetKey, cookieMap)
        val ttl = commands.ttl(key)
        if (ttl != null && ttl > 0) {
          commands.expire(targetKey, ttl)
        } else {
          commands.expire(targetKey, keyTtlSeconds)
        }
        commands.del(key)
      }
    }
  }

  private suspend fun <T> redis(block: () -> T): T = withContext(Dispatchers.IO) { block() }

  private fun serializeCookie(
    name: String,
    value: String,
    domain: String,
    path: String,
    expiresAt: Long?,
    secure: Boolean,
    httpOnly: Boolean,
    maxAge: Int,
    createdAt: Long,
  ): String {
    return listOf(
        escape(name),
        escape(value),
        escape(domain),
        escape(path),
        expiresAt?.toString().orEmpty(),
        secure.toString(),
        httpOnly.toString(),
        maxAge.toString(),
        createdAt.toString(),
      )
      .joinToString("\u001F")
  }

  private fun decodeCookie(encoded: String): StoredCookie? {
    val parts = encoded.split("\u001F")
    if (parts.size != 9) return null
    return StoredCookie(
      name = unescape(parts[0]),
      value = unescape(parts[1]),
      domain = unescape(parts[2]),
      path = unescape(parts[3]),
      expiresAt = parts[4].ifBlank { null }?.toLongOrNull(),
      secure = parts[5].toBooleanStrictOrNull() ?: false,
      httpOnly = parts[6].toBooleanStrictOrNull() ?: false,
      maxAge = parts[7].toIntOrNull() ?: -1,
      createdAt = parts[8].toLongOrNull() ?: 0L,
    )
  }

  private fun computeKeyTtlSeconds(expiresAt: Long?, maxAge: Int, createdAt: Long): Long {
    val now = System.currentTimeMillis()
    val cookieExpiryMs =
      listOfNotNull(
          expiresAt,
          maxAge.takeIf { it >= 0 }?.let { createdAt + it * 1000L },
        )
        .minOrNull()
    return if (cookieExpiryMs == null) {
      keyTtlSeconds
    } else {
      ((cookieExpiryMs - now) / 1000).coerceAtLeast(1L)
    }
  }

  private fun escape(value: String): String {
    return value
      .replace("\\", "\\\\")
      .replace("\u001F", "\\u001F")
      .replace("\n", "\\n")
  }

  private fun unescape(value: String): String {
    return value
      .replace("\\u001F", "\u001F")
      .replace("\\n", "\n")
      .replace("\\\\", "\\")
  }

  private fun isExpired(now: Long, expiresAt: Long?, maxAge: Int, createdAt: Long): Boolean {
    if (expiresAt != null && expiresAt <= now) return true
    if (maxAge >= 0 && now >= (createdAt + maxAge * 1000L)) return true
    return false
  }

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

  private fun isHttps(url: Url): Boolean = url.protocol.name.equals("https", true)

  private data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val maxAge: Int,
    val createdAt: Long,
  )

  companion object {
    private fun storageKey(subject: String): String = "cookies:$subject"

    private fun cookieField(name: String, domain: String, path: String): String = "$domain|$path|$name"

    suspend fun clearSubject(redisUri: String, subject: String) {
      withContext(Dispatchers.IO) {
        val client = RedisClient.create(redisUri)
        val connection = client.connect()
        try {
          connection.sync().del(storageKey(subject))
        } finally {
          runCatching { connection.close() }
          client.shutdown()
        }
      }
    }
  }
}
