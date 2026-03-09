package cn.edu.ubaa

import cn.edu.ubaa.auth.GlobalSessionManager
import cn.edu.ubaa.auth.JwtAuth
import cn.edu.ubaa.auth.JwtAuth.configureJwtAuth
import cn.edu.ubaa.auth.authRouting
import cn.edu.ubaa.bykc.GlobalBykcService
import cn.edu.ubaa.bykc.bykcRouting
import cn.edu.ubaa.classroom.classroomRouting
import cn.edu.ubaa.evaluation.evaluationRouting
import cn.edu.ubaa.exam.examRouting
import cn.edu.ubaa.schedule.scheduleRouting
import cn.edu.ubaa.signin.SigninService
import cn.edu.ubaa.signin.signinRouting
import cn.edu.ubaa.user.userRouting
import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheusmetrics.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** 后端服务入口函数。 负责加载环境变量、配置服务器端口并启动 Netty 引擎。 */
fun main() {
  val dotenv = dotenv { ignoreIfMissing = true }
  val serverPort = dotenv["SERVER_PORT"]?.toInt() ?: 5432
  val serverHost = dotenv["SERVER_BIND_HOST"] ?: "0.0.0.0"

  val log = LoggerFactory.getLogger("Application")
  log.info("Starting UBAA Server on $serverHost:$serverPort...")

  embeddedServer(Netty, port = serverPort, host = serverHost, module = Application::module)
    .start(wait = true)
}

val log = LoggerFactory.getLogger("Application")

/** 全局 Prometheus 指标注册表。 */
val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

/** Ktor 应用模块主配置。 负责安装插件（JWT, CORS, ContentNegotiation, Metrics）并注册业务路由。 */
fun Application.module() {
  log.info("Initializing Application module...")

  install(MicrometerMetrics) { registry = appMicrometerRegistry }
  install(CallLogging) { level = Level.INFO }
  configureJwtAuth()

  install(CORS) {
    allowMethod(HttpMethod.Options)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Delete)
    allowMethod(HttpMethod.Patch)
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.AccessControlAllowOrigin)
    anyHost()
  }

  install(ContentNegotiation) { json() }

  val sessionManager = GlobalSessionManager.instance
  val bykcService = GlobalBykcService.instance
  registerPerformanceGauges(sessionManager, bykcService)

  val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  cleanupScope.launch {
    while (isActive) {
      delay(5.minutes)
      val expiredSessions = sessionManager.cleanupExpiredSessions()
      val expiredPreLogin = sessionManager.cleanupExpiredPreLoginSessions()
      val expiredSigninClients = SigninService.cleanupExpiredClients()
      val expiredBykcClients = bykcService.cleanupExpiredClients()
      if (expiredSessions + expiredPreLogin + expiredSigninClients + expiredBykcClients > 0) {
        log.info(
          "Cleanup removed sessions={}, prelogin={}, signinClients={}, bykcClients={}",
          expiredSessions,
          expiredPreLogin,
          expiredSigninClients,
          expiredBykcClients,
        )
      }
    }
  }

  monitor.subscribe(ApplicationStopping) {
    cleanupScope.cancel()
    SigninService.closeAll()
    bykcService.clearCache()
    sessionManager.close()
  }

  routing {
    get("/metrics") { call.respondText(appMicrometerRegistry.scrape()) }

    authRouting()

    authenticate(JwtAuth.JWT_AUTH) {
      log.info("Registering authenticated routes...")
      userRouting()
      scheduleRouting()
      bykcRouting()
      examRouting()
      signinRouting()
      classroomRouting()
      evaluationRouting()
    }

    get("/") { call.respondText("Ktor: ${Greeting().greet()}") }
  }
  log.info("Application module initialized successfully.")
}

private fun registerPerformanceGauges(
  sessionManager: cn.edu.ubaa.auth.SessionManager,
  bykcService: cn.edu.ubaa.bykc.BykcService,
) {
  Gauge.builder("ubaa.sessions.active") { sessionManager.activeSessionCount().toDouble() }
    .register(appMicrometerRegistry)
  Gauge.builder("ubaa.sessions.prelogin") { sessionManager.preLoginSessionCount().toDouble() }
    .register(appMicrometerRegistry)
  Gauge.builder("ubaa.signin.cache") { SigninService.cacheSize().toDouble() }
    .register(appMicrometerRegistry)
  Gauge.builder("ubaa.bykc.cache") { bykcService.cacheSize().toDouble() }
    .register(appMicrometerRegistry)
}
