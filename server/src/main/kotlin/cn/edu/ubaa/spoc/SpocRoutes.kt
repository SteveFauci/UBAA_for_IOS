package cn.edu.ubaa.spoc

import cn.edu.ubaa.auth.ErrorDetails
import cn.edu.ubaa.auth.ErrorResponse
import cn.edu.ubaa.auth.JwtAuth.requireUserSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** 注册 SPOC 作业查询相关路由。 */
fun Route.spocRouting() {
  val spocService = GlobalSpocService.instance

  route("/api/v1/spoc") {
    get("/assignments") {
      val session = call.requireUserSession()
      call.runSpocCall {
        call.respond(HttpStatusCode.OK, spocService.getAssignments(session.username))
      }
    }

    get("/assignments/{assignmentId}") {
      val session = call.requireUserSession()
      val assignmentId =
        call.parameters["assignmentId"]?.takeIf { it.isNotBlank() }
          ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(ErrorDetails("invalid_request", "assignmentId is required")),
          )

      call.runSpocCall {
        call.respond(HttpStatusCode.OK, spocService.getAssignmentDetail(session.username, assignmentId))
      }
    }
  }
}

private suspend fun ApplicationCall.runSpocCall(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: SpocAuthenticationException) {
    respond(
      HttpStatusCode.BadGateway,
      ErrorResponse(ErrorDetails("spoc_auth_failed", e.message ?: "SPOC authentication failed")),
    )
  } catch (e: SpocException) {
    respond(
      HttpStatusCode.BadGateway,
      ErrorResponse(ErrorDetails("spoc_error", e.message ?: "SPOC request failed")),
    )
  } catch (e: Exception) {
    respond(
      HttpStatusCode.InternalServerError,
      ErrorResponse(ErrorDetails("internal_server_error", "An unexpected server error occurred.")),
    )
  }
}
