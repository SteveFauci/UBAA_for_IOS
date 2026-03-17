package cn.edu.ubaa.spoc

import cn.edu.ubaa.model.dto.SpocAssignmentDetailDto
import cn.edu.ubaa.model.dto.SpocAssignmentSummaryDto
import cn.edu.ubaa.model.dto.SpocSubmissionStatus
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

internal open class SpocException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal class SpocAuthenticationException(message: String) : SpocException(message)

internal data class SpocLoginTokens(
  val token: String,
  val refreshToken: String? = null,
)

internal object SpocParsers {
  fun extractLoginTokens(url: String): SpocLoginTokens? {
    val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
    if (!uri.path.orEmpty().contains("/spocnew/cas")) return null
    val query = uri.rawQuery.orEmpty()
    if (query.isBlank()) return null

    val params =
      query.split("&").mapNotNull { part ->
        val pieces = part.split("=", limit = 2)
        if (pieces.isEmpty()) null else pieces[0] to pieces.getOrNull(1).orEmpty()
      }.toMap()

    val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
    val refreshToken = params["refreshToken"]?.takeIf { it.isNotBlank() }
    return SpocLoginTokens(token = token, refreshToken = refreshToken)
  }

  fun resolveRoleCode(content: SpocCasLoginContent): String? {
    return content.jsdm?.takeIf { it.isNotBlank() }
      ?: firstString(content.rolecode)
      ?: firstString(content.jsdmList)
  }

  fun toPlainText(html: String?): String? {
    if (html.isNullOrBlank()) return null
    return Jsoup.parse(html).text().replace(Regex("\\s+"), " ").trim().ifBlank { null }
  }

  fun mapSubmissionStatus(rawStatus: String?, hasContent: Boolean): SpocSubmissionStatus {
    return when {
      !hasContent -> SpocSubmissionStatus.UNSUBMITTED
      rawStatus == "1" -> SpocSubmissionStatus.SUBMITTED
      rawStatus == "0" -> SpocSubmissionStatus.UNSUBMITTED
      else -> SpocSubmissionStatus.UNKNOWN
    }
  }

  fun submissionStatusText(status: SpocSubmissionStatus, rawStatus: String? = null): String {
    return when (status) {
      SpocSubmissionStatus.SUBMITTED -> "已提交"
      SpocSubmissionStatus.UNSUBMITTED -> "未提交"
      SpocSubmissionStatus.UNKNOWN -> rawStatus?.takeIf { it.isNotBlank() }?.let { "未知状态($it)" } ?: "未知状态"
    }
  }

  private fun firstString(element: JsonElement?): String? {
    return when (element) {
      null -> null
      is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
      else -> element.jsonArray.firstNotNullOfOrNull { it.jsonPrimitive.contentOrNull?.takeIf { value -> value.isNotBlank() } }
    }
  }
}

internal fun SpocAssignmentSummaryDto.toDetail(
  contentPlainText: String?,
  contentHtml: String?,
  submittedAt: String?,
): SpocAssignmentDetailDto {
  return SpocAssignmentDetailDto(
    assignmentId = assignmentId,
    courseId = courseId,
    courseName = courseName,
    teacherName = teacherName,
    title = title,
    startTime = startTime,
    dueTime = dueTime,
    score = score,
    submissionStatus = submissionStatus,
    submissionStatusText = submissionStatusText,
    contentPlainText = contentPlainText,
    contentHtml = contentHtml,
    submittedAt = submittedAt,
  )
}

@Serializable
internal data class SpocEnvelope<T>(
  val code: Int,
  val msg: String? = null,
  @SerialName("msg_en") val msgEn: String? = null,
  val content: T? = null,
)

@Serializable
internal data class SpocCasLoginRequest(val token: String)

@Serializable
internal data class SpocQueryOneRequest(val param: String)

@Serializable
internal data class SpocCurrentTermContent(
  val dqxq: String? = null,
  val mrxq: String? = null,
)

@Serializable
internal data class SpocCourseRaw(
  val kcid: String,
  val kcmc: String,
  val skjs: String? = null,
)

@Serializable
internal data class SpocAssignmentListContent(
  val list: List<SpocAssignmentRaw> = emptyList(),
)

@Serializable
internal data class SpocAssignmentRaw(
  val id: String,
  val zymc: String,
  val zykssj: String? = null,
  val zyjzsj: String? = null,
  val zyfs: String? = null,
  val sskcid: String? = null,
)

@Serializable
internal data class SpocAssignmentDetailRaw(
  val id: String,
  val zymc: String,
  val zynr: String? = null,
  val zykssj: String? = null,
  val zyjzsj: String? = null,
  val zyfs: String? = null,
  val sskcid: String? = null,
)

@Serializable
internal data class SpocSubmissionRaw(
  val tjzt: String? = null,
  val tjsj: String? = null,
)

@Serializable
internal data class SpocCasLoginContent(
  val jsdm: String? = null,
  val rolecode: JsonElement? = null,
  val jsdmList: JsonElement? = null,
)
