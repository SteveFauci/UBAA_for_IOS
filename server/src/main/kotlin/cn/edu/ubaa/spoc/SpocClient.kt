package cn.edu.ubaa.spoc

import cn.edu.ubaa.auth.GlobalSessionManager
import cn.edu.ubaa.auth.SessionManager
import cn.edu.ubaa.utils.VpnCipher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** SPOC 原始客户端。负责基于已登录的 SSO 会话完成 SPOC 二次登录并发起业务请求。 */
internal open class SpocClient(private val username: String) {
  private val log = LoggerFactory.getLogger(SpocClient::class.java)
  private val sessionManager: SessionManager = GlobalSessionManager.instance
  private val json = Json { ignoreUnknownKeys = true }

  private var token: String? = null
  private var refreshToken: String? = null
  private var roleCode: String? = null

  suspend fun getCurrentTerm(): SpocCurrentTermContent {
    return withAuthenticatedCall {
      postEnvelope<SpocCurrentTermContent, SpocQueryOneRequest>(
        "https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne",
        SpocQueryOneRequest(CURRENT_TERM_PARAM),
      )
    }
  }

  suspend fun getCourses(termCode: String): List<SpocCourseRaw> {
    return withAuthenticatedCall {
      getEnvelope<List<SpocCourseRaw>>("https://spoc.buaa.edu.cn/spocnewht/jxkj/queryKclb") {
        parameter("kcmc", "")
        parameter("xnxq", termCode)
      }
    }
  }

  suspend fun getAssignments(courseId: String): List<SpocAssignmentRaw> {
    return withAuthenticatedCall {
      getEnvelope<SpocAssignmentListContent>("https://spoc.buaa.edu.cn/spocnewht/kczy/queryXsZyList") {
        parameter("sskcid", courseId)
        parameter("flag", 1)
        parameter("sflx", 2)
      }.list
    }
  }

  suspend fun getAssignmentDetail(assignmentId: String): SpocAssignmentDetailRaw {
    return withAuthenticatedCall {
      getEnvelope<SpocAssignmentDetailRaw>("https://spoc.buaa.edu.cn/spocnewht/kczy/queryKczyInfoByid") {
        parameter("id", assignmentId)
      }
    }
  }

  suspend fun getSubmission(assignmentId: String): SpocSubmissionRaw? {
    return withAuthenticatedCall {
      getEnvelope<SpocSubmissionRaw?>("https://spoc.buaa.edu.cn/spocnewht/kczy/queryXsSubmitKczyInfo") {
        parameter("kczyid", assignmentId)
      }
    }
  }

  fun close() {
    token = null
    refreshToken = null
    roleCode = null
  }

  private suspend fun ensureLogin(forceRefresh: Boolean = false) {
    if (!forceRefresh && !token.isNullOrBlank() && !roleCode.isNullOrBlank()) return

    val session = sessionManager.requireSession(username)
    val tokens = fetchLoginTokens(session.client)
    val casLogin = performCasLogin(session.client, tokens.token)
    val resolvedRoleCode =
      SpocParsers.resolveRoleCode(casLogin)
        ?: throw SpocAuthenticationException("SPOC 登录成功但未获取到角色信息")

    token = tokens.token
    refreshToken = tokens.refreshToken
    roleCode = resolvedRoleCode
    log.debug("SPOC login established for user: {}", username)
  }

  private suspend fun fetchLoginTokens(baseClient: HttpClient): SpocLoginTokens {
    val noRedirectClient = baseClient.config { followRedirects = false }
    try {
      var currentUrl = VpnCipher.toVpnUrl("https://spoc.buaa.edu.cn/spocnewht/cas")
      repeat(8) {
        val response = noRedirectClient.get(currentUrl)
        SpocParsers.extractLoginTokens(response.call.request.url.toString())?.let { return it }

        val location = response.headers[HttpHeaders.Location]
          ?: throw SpocAuthenticationException("SPOC 登录跳转缺少 Location")
        SpocParsers.extractLoginTokens(location)?.let { return it }
        currentUrl = normalizeUrl(resolveUrl(response.call.request.url.toString(), location))
      }
      throw SpocAuthenticationException("未能在 SPOC 登录跳转链中获取 token")
    } finally {
      noRedirectClient.close()
    }
  }

  private suspend fun performCasLogin(baseClient: HttpClient, loginToken: String): SpocCasLoginContent {
    val response =
      baseClient.post(normalizeUrl("https://spoc.buaa.edu.cn/spocnewht/sys/casLogin")) {
        contentType(ContentType.Application.Json)
        header("X-Requested-With", "XMLHttpRequest")
        header("Token", "Inco-$loginToken")
        setBody(SpocCasLoginRequest(loginToken))
      }
    val bodyText = response.bodyAsText()
    val envelope = decodeEnvelope<SpocCasLoginContent>(bodyText)
    return unwrapEnvelope(envelope, bodyText)
  }

  private suspend inline fun <reified T> withAuthenticatedCall(
    crossinline block: suspend () -> T
  ): T {
    ensureLogin()
    return try {
      block()
    } catch (e: SpocAuthenticationException) {
      log.info("SPOC auth expired for user {}, retrying once", username)
      ensureLogin(forceRefresh = true)
      block()
    }
  }

  private suspend inline fun <reified T> getEnvelope(
    url: String,
    crossinline builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
  ): T {
    val currentToken = token ?: throw SpocAuthenticationException("SPOC token 未初始化")
    val currentRoleCode = roleCode ?: throw SpocAuthenticationException("SPOC roleCode 未初始化")
    val session = sessionManager.requireSession(username)
    val response =
      session.client.get(normalizeUrl(url)) {
        header("X-Requested-With", "XMLHttpRequest")
        header("Token", "Inco-$currentToken")
        header("RoleCode", currentRoleCode)
        builder()
      }
    val bodyText = response.bodyAsText()
    val envelope = decodeEnvelope<T>(bodyText)
    return unwrapEnvelope(envelope, bodyText)
  }

  private suspend inline fun <reified T, reified B : Any> postEnvelope(url: String, body: B): T {
    val currentToken = token ?: throw SpocAuthenticationException("SPOC token 未初始化")
    val currentRoleCode = roleCode ?: throw SpocAuthenticationException("SPOC roleCode 未初始化")
    val session = sessionManager.requireSession(username)
    val response =
      session.client.post(normalizeUrl(url)) {
        contentType(ContentType.Application.Json)
        header("X-Requested-With", "XMLHttpRequest")
        header("Token", "Inco-$currentToken")
        header("RoleCode", currentRoleCode)
        setBody(body)
      }
    val bodyText = response.bodyAsText()
    val envelope = decodeEnvelope<T>(bodyText)
    return unwrapEnvelope(envelope, bodyText)
  }

  private inline fun <reified T> decodeEnvelope(bodyText: String): SpocEnvelope<T> {
    return try {
      json.decodeFromString<SpocEnvelope<T>>(bodyText)
    } catch (e: Exception) {
      if (looksLikeAuthenticationFailure("decode_failure", bodyText)) {
        throw SpocAuthenticationException("SPOC 登录状态已失效")
      }
      throw SpocException("SPOC 响应解析失败", e)
    }
  }

  private fun <T> unwrapEnvelope(envelope: SpocEnvelope<T>, bodyText: String): T {
    if (envelope.code == 200 && envelope.content != null) return envelope.content
    if (envelope.code == 200 && bodyText.contains("\"content\":null")) {
      @Suppress("UNCHECKED_CAST")
      return null as T
    }

    val message = envelope.msg ?: envelope.msgEn ?: "SPOC 请求失败"
    if (looksLikeAuthenticationFailure(message, bodyText)) {
      throw SpocAuthenticationException(message)
    }
    throw SpocException(message)
  }

  private fun looksLikeAuthenticationFailure(message: String, bodyText: String): Boolean {
    val text = "$message $bodyText"
    return listOf("登录", "token", "未认证", "未登录", "权限").any { text.contains(it, ignoreCase = true) }
  }

  private fun resolveUrl(baseUrl: String, location: String): String {
    return URI.create(baseUrl).resolve(location).toString()
  }

  private fun normalizeUrl(url: String): String = VpnCipher.toVpnUrl(url)

  companion object {
    private const val CURRENT_TERM_PARAM =
      "YHrxtTavu6raCwC0/qdgYffB9evWHBkTng/XS4W6j3f/TPo02iEPSoegscDTRNzIPRG49o3RHl4JiFCXAiBkkA=="
  }
}
