package cn.edu.ubaa.api

import cn.edu.ubaa.model.dto.SpocAssignmentDetailDto
import cn.edu.ubaa.model.dto.SpocAssignmentsResponse
import io.ktor.client.request.get

/**
 * SPOC 作业查询 API。
 *
 * @param apiClient 使用的 API 客户端实例。
 */
open class SpocApi(private val apiClient: ApiClient = ApiClientProvider.shared) {

  /** 获取当前默认学期的所有作业摘要。 */
  open suspend fun getAssignments(): Result<SpocAssignmentsResponse> {
    return safeApiCall { apiClient.getClient().get("api/v1/spoc/assignments") }
  }

  /** 获取指定作业的详细信息。 */
  open suspend fun getAssignmentDetail(
    assignmentId: String
  ): Result<SpocAssignmentDetailDto> {
    return safeApiCall { apiClient.getClient().get("api/v1/spoc/assignments/$assignmentId") }
  }
}
