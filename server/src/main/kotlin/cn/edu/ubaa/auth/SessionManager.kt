package cn.edu.ubaa.auth

import cn.edu.ubaa.model.dto.UserData
import cn.edu.ubaa.utils.JwtUtil
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

interface SessionPersistence {
  data class SessionRecord(
    val userData: UserData,
    val authenticatedAt: Instant,
    val lastActivity: Instant,
  )

  suspend fun saveSession(
    username: String,
    userData: UserData,
    authenticatedAt: Instant,
    lastActivity: Instant,
  )

  suspend fun updateLastActivity(username: String, lastActivity: Instant)

  suspend fun loadSession(username: String): SessionRecord?

  suspend fun deleteSession(username: String)

  fun close()
}

interface ManagedCookieStorage : CookiesStorage {
  suspend fun clear()

  suspend fun migrateTo(newSubject: String)
}

interface ManagedCookieStorageFactory {
  fun create(subject: String): ManagedCookieStorage

  suspend fun clearSubject(subject: String)
}

/** 会话管理器。 负责隔离不同用户的 HttpClient 实例、Cookie 存储，以及管理 JWT 与用户会话之间的映射关系。 支持会话持久化到 Redis，实现重启后的会话恢复。 */
class SessionManager(
  private val sessionTtl: Duration = Duration.ofMinutes(30),
  private val redisUri: String = DEFAULT_REDIS_URI,
  private val activityPersistInterval: Duration = Duration.ofSeconds(60),
  private val sessionStore: SessionPersistence = RedisSessionStore(redisUri),
  private val cookieStorageFactory: ManagedCookieStorageFactory = RedisCookieStorageFactory(redisUri),
) {

  enum class SessionAccess {
    READ_ONLY,
    TOUCH,
  }

  /** 登录过程中的临时会话载体。 */
  data class SessionCandidate(
    val username: String,
    val client: HttpClient,
    val cookieStorage: ManagedCookieStorage,
  )

  /** 活跃的用户会话。 封装了用户的认证信息、专属客户端以及活动时间追踪。 */
  class UserSession(
    val username: String,
    val client: HttpClient,
    val cookieStorage: ManagedCookieStorage,
    val userData: UserData,
    val authenticatedAt: Instant,
    initialActivity: Instant = authenticatedAt,
  ) {
    @Volatile private var lastActivity: Instant = initialActivity
    @Volatile private var lastPersistedActivity: Instant = initialActivity

    fun isExpired(ttl: Duration): Boolean = Instant.now().isAfter(lastActivity.plus(ttl))

    fun markActive(now: Instant = Instant.now()) {
      lastActivity = now
    }

    fun shouldPersistActivity(now: Instant, minInterval: Duration): Boolean {
      return !now.isBefore(lastPersistedActivity.plus(minInterval))
    }

    fun markPersistedActivity(at: Instant = lastActivity) {
      lastPersistedActivity = at
    }

    fun lastActivity(): Instant = lastActivity
  }

  /** 包含会话对象和关联 JWT 的组合。 */
  data class SessionWithToken(val session: UserSession, val jwtToken: String)

  /** 预登录会话：用于 preload 阶段，此时用户尚未输入凭据，通过 clientId 标识。 */
  data class PreLoginCandidate(
    val clientId: String,
    val client: HttpClient,
    val cookieStorage: ManagedCookieStorage,
    val createdAt: Instant = Instant.now(),
  ) {
    fun isExpired(ttl: Duration): Boolean = Instant.now().isAfter(createdAt.plus(ttl))
  }

  private val sessions = ConcurrentHashMap<String, UserSession>()
  private val preLoginSessions = ConcurrentHashMap<String, PreLoginCandidate>()
  private val preLoginTtl: Duration = Duration.ofMinutes(5)

  suspend fun preparePreLoginSession(clientId: String): PreLoginCandidate {
    preLoginSessions[clientId]?.let { existing ->
      if (!existing.isExpired(preLoginTtl)) return existing
      if (preLoginSessions.remove(clientId, existing)) {
        disposePreLoginCandidate(existing, clearCookies = true)
      }
    }

    val cookieStorage = cookieStorageFactory.create(preLoginSubject(clientId))
    cookieStorage.clear()

    val client = buildClient(cookieStorage)
    val candidate = PreLoginCandidate(clientId, client, cookieStorage)
    preLoginSessions[clientId] = candidate
    return candidate
  }

  suspend fun promotePreLoginSession(clientId: String, username: String): SessionCandidate? {
    val preLogin = preLoginSessions.remove(clientId) ?: return null
    if (preLogin.isExpired(preLoginTtl)) {
      disposePreLoginCandidate(preLogin, clearCookies = true)
      return null
    }

    preLogin.cookieStorage.migrateTo(username)

    val newCookieStorage = cookieStorageFactory.create(username)
    val newClient = buildClient(newCookieStorage)
    preLogin.client.close()
    closeCookieStorage(preLogin.cookieStorage)

    return SessionCandidate(username, newClient, newCookieStorage)
  }

  suspend fun prepareSession(username: String): SessionCandidate {
    val cookieStorage = cookieStorageFactory.create(username)
    cookieStorage.clear()
    val client = buildClient(cookieStorage)
    return SessionCandidate(username, client, cookieStorage)
  }

  suspend fun commitSessionWithToken(candidate: SessionCandidate, userData: UserData): SessionWithToken {
    val newSession =
      UserSession(
        username = candidate.username,
        client = candidate.client,
        cookieStorage = candidate.cookieStorage,
        userData = userData,
        authenticatedAt = Instant.now(),
      )

    val jwtToken = JwtUtil.generateToken(candidate.username, sessionTtl)
    sessions.compute(candidate.username) { _, previous ->
      previous?.client?.close()
      closeCookieStorage(previous?.cookieStorage)
      newSession
    }

    sessionStore.saveSession(
      username = candidate.username,
      userData = userData,
      authenticatedAt = newSession.authenticatedAt,
      lastActivity = newSession.lastActivity(),
    )

    return SessionWithToken(newSession, jwtToken)
  }

  suspend fun getSession(
    username: String,
    access: SessionAccess = SessionAccess.TOUCH,
  ): UserSession? {
    val active = sessions[username] ?: restoreSession(username) ?: return null
    if (active.isExpired(sessionTtl)) {
      invalidateSession(username)
      return null
    }
    if (access == SessionAccess.TOUCH) {
      touchSession(username, active)
    }
    sessions[username] = active
    return active
  }

  suspend fun getSessionByToken(
    jwtToken: String,
    access: SessionAccess = SessionAccess.TOUCH,
  ): UserSession? {
    val username = JwtUtil.validateTokenAndGetUsername(jwtToken) ?: return null
    return getSession(username, access)
  }

  suspend fun requireSession(username: String): UserSession {
    return getSession(username, SessionAccess.TOUCH)
      ?: throw RuntimeException("Session expired or invalid for user: $username")
  }

  suspend fun invalidateSession(username: String) {
    val existing = sessions.remove(username)
    existing?.client?.close()
    clearAndCloseCookieStorage(username, existing?.cookieStorage)
    sessionStore.deleteSession(username)
  }

  suspend fun cleanupExpiredSessions(): Int {
    var removed = 0
    for ((username, session) in sessions.entries.toList()) {
      if (!session.isExpired(sessionTtl)) continue
      if (!sessions.remove(username, session)) continue
      session.client.close()
      clearAndCloseCookieStorage(username, session.cookieStorage)
      sessionStore.deleteSession(username)
      removed++
    }
    return removed
  }

  suspend fun cleanupExpiredPreLoginSessions(): Int {
    var removed = 0
    for ((clientId, candidate) in preLoginSessions.entries.toList()) {
      if (!candidate.isExpired(preLoginTtl)) continue
      if (!preLoginSessions.remove(clientId, candidate)) continue
      disposePreLoginCandidate(candidate, clearCookies = true)
      removed++
    }
    return removed
  }

  suspend fun cleanupPreLoginSession(clientId: String) {
    val candidate = preLoginSessions.remove(clientId) ?: return
    disposePreLoginCandidate(candidate, clearCookies = true)
  }

  fun activeSessionCount(): Int = sessions.size

  fun preLoginSessionCount(): Int = preLoginSessions.size

  fun close() {
    sessions.values.forEach {
      it.client.close()
      closeCookieStorage(it.cookieStorage)
    }
    sessions.clear()

    preLoginSessions.values.forEach {
      it.client.close()
      closeCookieStorage(it.cookieStorage)
    }
    preLoginSessions.clear()

    sessionStore.close()
  }

  private suspend fun touchSession(username: String, session: UserSession) {
    val now = Instant.now()
    session.markActive(now)
    if (session.shouldPersistActivity(now, activityPersistInterval)) {
      sessionStore.updateLastActivity(username, now)
      session.markPersistedActivity(now)
    }
  }

  private fun buildClient(cookieStorage: CookiesStorage): HttpClient {
    return HttpClient(CIO) {
      engine {
        val proxyUrl = System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY")
        if (!proxyUrl.isNullOrBlank()) {
          proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(proxyUrl))
        }
        if (System.getenv("TRUST_ALL_CERTS")?.lowercase() == "true") {
          https {
            trustManager =
              object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                  c: Array<java.security.cert.X509Certificate>?,
                  a: String?,
                ) {}

                override fun checkServerTrusted(
                  c: Array<java.security.cert.X509Certificate>?,
                  a: String?,
                ) {}

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                  arrayOf()
              }
          }
        }
      }
      install(HttpCookies) { storage = cookieStorage }
      install(ContentNegotiation) {
        json(
          Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
          }
        )
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
      }
      followRedirects = true
      defaultRequest {
        headers.append(HttpHeaders.UserAgent, "UBAA-Backend/1.0")
        headers.append(HttpHeaders.Accept, "application/json, text/html, */*")
      }
    }
  }

  private suspend fun restoreSession(username: String): UserSession? {
    val record = sessionStore.loadSession(username) ?: return null
    val cookieStorage = cookieStorageFactory.create(username)
    val client = buildClient(cookieStorage)
    return UserSession(
        username = username,
        client = client,
        cookieStorage = cookieStorage,
        userData = record.userData,
        authenticatedAt = record.authenticatedAt,
        initialActivity = record.lastActivity,
      )
      .also { sessions[username] = it }
  }

  private suspend fun disposePreLoginCandidate(candidate: PreLoginCandidate, clearCookies: Boolean) {
    candidate.client.close()
    if (clearCookies) {
      clearAndCloseCookieStorage(preLoginSubject(candidate.clientId), candidate.cookieStorage)
    } else {
      closeCookieStorage(candidate.cookieStorage)
    }
  }

  private suspend fun clearAndCloseCookieStorage(subject: String, storage: ManagedCookieStorage?) {
    if (storage != null) {
      storage.clear()
      closeCookieStorage(storage)
    } else {
      cookieStorageFactory.clearSubject(subject)
    }
  }

  private fun closeCookieStorage(storage: ManagedCookieStorage?) {
    runCatching { storage?.close() }
  }

  private fun preLoginSubject(clientId: String): String = "prelogin_$clientId"

  companion object {
    private val DEFAULT_REDIS_URI: String = System.getenv("REDIS_URI") ?: "redis://localhost:6379"
  }
}

/** 全局会话管理器单例。 */
object GlobalSessionManager {
  val instance: SessionManager by lazy { SessionManager() }
}
