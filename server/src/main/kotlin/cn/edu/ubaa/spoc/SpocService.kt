package cn.edu.ubaa.spoc

import cn.edu.ubaa.model.dto.SpocAssignmentDetailDto
import cn.edu.ubaa.model.dto.SpocAssignmentSummaryDto
import cn.edu.ubaa.model.dto.SpocAssignmentsResponse
import cn.edu.ubaa.model.dto.SpocSubmissionStatus
import java.util.concurrent.ConcurrentHashMap

/** SPOC 作业业务服务。 */
internal class SpocService(private val clientProvider: (String) -> SpocClient = ::SpocClient) {
  private data class CachedClient(
    val client: SpocClient,
    @Volatile var lastAccessAt: Long,
  )

  private val clientCache = ConcurrentHashMap<String, CachedClient>()

  suspend fun getAssignments(username: String): SpocAssignmentsResponse {
    val client = getClient(username)
    val term = client.getCurrentTerm()
    val termCode = term.mrxq ?: throw SpocException("无法获取 SPOC 当前学期代码")
    val courses = client.getCourses(termCode)

    val assignments =
      courses
        .flatMap { course ->
          client.getAssignments(course.kcid).map { assignment ->
            val submission = runCatching { client.getSubmission(assignment.id) }.getOrNull()
            val hasSubmission = submission != null
            val status = SpocParsers.mapSubmissionStatus(submission?.tjzt, hasSubmission)
            SpocAssignmentSummaryDto(
              assignmentId = assignment.id,
              courseId = course.kcid,
              courseName = course.kcmc,
              teacherName = course.skjs,
              title = assignment.zymc,
              startTime = assignment.zykssj,
              dueTime = assignment.zyjzsj,
              score = assignment.zyfs,
              submissionStatus = status,
              submissionStatusText = SpocParsers.submissionStatusText(status, submission?.tjzt),
            )
          }
        }
        .sortedWith(compareBy<SpocAssignmentSummaryDto> { it.dueTime ?: "9999-99-99 99:99:99" }.thenBy { it.courseName }.thenBy { it.title })

    return SpocAssignmentsResponse(
      termCode = termCode,
      termName = term.dqxq,
      assignments = assignments,
    )
  }

  suspend fun getAssignmentDetail(username: String, assignmentId: String): SpocAssignmentDetailDto {
    val summary =
      getAssignments(username).assignments.firstOrNull { it.assignmentId == assignmentId }
        ?: throw SpocException("未找到指定的 SPOC 作业")

    val client = getClient(username)
    val detail = client.getAssignmentDetail(assignmentId)
    val submission = runCatching { client.getSubmission(assignmentId) }.getOrNull()
    val hasSubmission = submission != null
    val status = SpocParsers.mapSubmissionStatus(submission?.tjzt, hasSubmission)

    return summary
      .copy(
        score = detail.zyfs ?: summary.score,
        startTime = detail.zykssj ?: summary.startTime,
        dueTime = detail.zyjzsj ?: summary.dueTime,
        submissionStatus = status,
        submissionStatusText = SpocParsers.submissionStatusText(status, submission?.tjzt),
      )
      .toDetail(
        contentPlainText = SpocParsers.toPlainText(detail.zynr),
        contentHtml = detail.zynr,
        submittedAt = submission?.tjsj,
      )
  }

  fun cleanupExpiredClients(maxIdleMillis: Long = DEFAULT_MAX_IDLE_MILLIS): Int {
    val cutoff = System.currentTimeMillis() - maxIdleMillis
    var removed = 0
    for ((username, cached) in clientCache.entries.toList()) {
      if (cached.lastAccessAt >= cutoff) continue
      if (!clientCache.remove(username, cached)) continue
      cached.client.close()
      removed++
    }
    return removed
  }

  fun cacheSize(): Int = clientCache.size

  fun clearCache() {
    clientCache.values.forEach { it.client.close() }
    clientCache.clear()
  }

  private fun getClient(username: String): SpocClient {
    val now = System.currentTimeMillis()
    return clientCache.compute(username) { _, existing ->
      existing?.also { it.lastAccessAt = now } ?: CachedClient(clientProvider(username), now)
    }!!.client
  }

  companion object {
    private const val DEFAULT_MAX_IDLE_MILLIS = 30 * 60 * 1000L
  }
}

internal object GlobalSpocService {
  val instance: SpocService by lazy { SpocService() }
}
