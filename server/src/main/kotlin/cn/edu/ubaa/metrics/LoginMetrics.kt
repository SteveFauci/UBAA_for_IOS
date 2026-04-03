package cn.edu.ubaa.metrics

import cn.edu.ubaa.auth.AuthConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.micrometer.core.instrument.MeterRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

enum class LoginSuccessMode(val tagValue: String) {
  MANUAL("manual"),
  PRELOAD_AUTO("preload_auto"),
}

enum class LoginMetricWindow(val tagValue: String, val hours: Long) {
  ONE_HOUR("1h", 1),
  TWENTY_FOUR_HOURS("24h", 24),
  SEVEN_DAYS("7d", 24 * 7),
  THIRTY_DAYS("30d", 24 * 30),
}

interface LoginMetricsSink {
  suspend fun recordSuccess(username: String, mode: LoginSuccessMode)
}

object NoOpLoginMetricsSink : LoginMetricsSink {
  override suspend fun recordSuccess(username: String, mode: LoginSuccessMode) = Unit
}

interface LoginStatsStore {
  suspend fun recordLogin(username: String, recordedAt: Instant)

  fun countEvents(window: LoginMetricWindow, now: Instant): Long

  fun countUniqueUsers(window: LoginMetricWindow, now: Instant): Long

  fun close()
}

class LoginMetricsRecorder(
    private val store: LoginStatsStore,
    private val registry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : LoginMetricsSink {
  private val log = LoggerFactory.getLogger(LoginMetricsRecorder::class.java)

  fun bindMetrics() {
    for (window in LoginMetricWindow.entries) {
      GaugeBindings.bind(
          registry = registry,
          name = "ubaa.auth.login.events.window",
          tags = mapOf("window" to window.tagValue),
      ) {
        store.countEvents(window, clock.instant()).toDouble()
      }

      GaugeBindings.bind(
          registry = registry,
          name = "ubaa.auth.login.unique.users.window",
          tags = mapOf("window" to window.tagValue),
      ) {
        store.countUniqueUsers(window, clock.instant()).toDouble()
      }
    }
  }

  override suspend fun recordSuccess(username: String, mode: LoginSuccessMode) {
    registry.counter("ubaa.auth.login.success", "mode", mode.tagValue).increment()
    try {
      store.recordLogin(username, clock.instant())
    } catch (e: Exception) {
      log.warn("Failed to persist login statistics for user {}", username, e)
    }
  }

  fun close() {
    store.close()
  }
}

class RedisLoginStatsStore(
    private val redisUri: String = AuthConfig.redisUri,
) : LoginStatsStore {
  private val client: RedisClient by lazy { RedisClient.create(redisUri) }
  private val connection: StatefulRedisConnection<String, String> by lazy { client.connect() }
  private val commands: RedisCommands<String, String> by lazy { connection.sync() }
  private val keyTtl = Duration.ofDays(32)

  override suspend fun recordLogin(username: String, recordedAt: Instant) {
    val bucket = bucketOf(recordedAt)
    val eventKey = eventKey(bucket)
    val uniqueKey = uniqueKey(bucket)
    val usernameHash = hashUsername(username)
    val ttlSeconds = keyTtl.seconds.coerceAtLeast(1L)

    withContext(Dispatchers.IO) {
      commands.incr(eventKey)
      commands.expire(eventKey, ttlSeconds)
      commands.pfadd(uniqueKey, usernameHash)
      commands.expire(uniqueKey, ttlSeconds)
    }
  }

  override fun countEvents(window: LoginMetricWindow, now: Instant): Long {
    val buckets = bucketsFor(window, now)
    return runCatching {
          buckets.sumOf { bucket -> commands.get(eventKey(bucket))?.toLongOrNull() ?: 0L }
        }
        .getOrDefault(0L)
  }

  override fun countUniqueUsers(window: LoginMetricWindow, now: Instant): Long {
    val keys = bucketsFor(window, now).map(::uniqueKey)
    return if (keys.isEmpty()) {
      0L
    } else {
      runCatching { commands.pfcount(*keys.toTypedArray()) }.getOrDefault(0L)
    }
  }

  override fun close() {
    runCatching { connection.close() }
    runCatching { client.shutdown() }
  }

  private fun bucketsFor(window: LoginMetricWindow, now: Instant): LongRange {
    val currentBucket = bucketOf(now)
    val firstBucket = currentBucket - window.hours + 1
    return firstBucket..currentBucket
  }

  private fun bucketOf(at: Instant): Long = at.epochSecond / 3600

  private fun eventKey(bucket: Long): String = "metrics:login:events:$bucket"

  private fun uniqueKey(bucket: Long): String = "metrics:login:users:$bucket"

  private fun hashUsername(username: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(username.toByteArray(StandardCharsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
  }
}

class InMemoryLoginStatsStore : LoginStatsStore {
  private data class LoginBucket(
      var events: Long = 0,
      val users: MutableSet<String> = linkedSetOf(),
  )

  private val buckets = ConcurrentHashMap<Long, LoginBucket>()

  override suspend fun recordLogin(username: String, recordedAt: Instant) {
    val bucket = buckets.computeIfAbsent(bucketOf(recordedAt)) { LoginBucket() }
    bucket.events += 1
    bucket.users += hashUsername(username)
  }

  override fun countEvents(window: LoginMetricWindow, now: Instant): Long {
    return bucketsFor(window, now).sumOf { bucket -> buckets[bucket]?.events ?: 0L }
  }

  override fun countUniqueUsers(window: LoginMetricWindow, now: Instant): Long {
    return bucketsFor(window, now)
        .flatMapTo(linkedSetOf()) { bucket -> buckets[bucket]?.users.orEmpty() }
        .size
        .toLong()
  }

  override fun close() {
    buckets.clear()
  }

  private fun bucketsFor(window: LoginMetricWindow, now: Instant): LongRange {
    val currentBucket = bucketOf(now)
    val firstBucket = currentBucket - window.hours + 1
    return firstBucket..currentBucket
  }

  private fun bucketOf(at: Instant): Long = at.epochSecond / 3600

  private fun hashUsername(username: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(username.toByteArray(StandardCharsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
  }
}
