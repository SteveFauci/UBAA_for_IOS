package cn.edu.ubaa.ygdk

import cn.edu.ubaa.auth.ErrorDetails
import cn.edu.ubaa.auth.ErrorResponse
import cn.edu.ubaa.auth.JwtAuth.jwtUsername
import cn.edu.ubaa.model.dto.YgdkClockinSubmitRequest
import cn.edu.ubaa.model.dto.YgdkPhotoUpload
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray

internal fun Route.ygdkRouting(ygdkService: YgdkService = GlobalYgdkService.instance) {
  route("/api/v1/ygdk") {
    get("/overview") {
      val username = call.jwtUsername!!
      try {
        call.respond(HttpStatusCode.OK, ygdkService.getOverview(username))
      } catch (e: YgdkException) {
        call.respondYgdkError(e)
      }
    }

    get("/records") {
      val username = call.jwtUsername!!
      val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
      val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
      try {
        call.respond(HttpStatusCode.OK, ygdkService.getRecords(username, page, size))
      } catch (e: YgdkException) {
        call.respondYgdkError(e)
      }
    }

    post("/records") {
      val username = call.jwtUsername!!
      val request =
          try {
            call.parseClockinRequest()
          } catch (_: Exception) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails("invalid_request", "无效的打卡提交内容")),
            )
          }
      try {
        call.respond(HttpStatusCode.OK, ygdkService.submitClockin(username, request))
      } catch (e: YgdkException) {
        call.respondYgdkError(e)
      }
    }
  }
}

private suspend fun ApplicationCall.parseClockinRequest(): YgdkClockinSubmitRequest {
  var itemId: Int? = null
  var startTime: String? = null
  var endTime: String? = null
  var place: String? = null
  var shareToSquare: Boolean? = null
  var photo: YgdkPhotoUpload? = null

  receiveMultipart().forEachPart { part ->
    when (part) {
      is PartData.FormItem ->
          when (part.name) {
            "itemId" -> itemId = part.value.toIntOrNull()
            "startTime" -> startTime = part.value
            "endTime" -> endTime = part.value
            "place" -> place = part.value
            "shareToSquare" -> shareToSquare = part.value.toBooleanStrictOrNull()
          }
      is PartData.FileItem ->
          if (part.name == "photo") {
            val bytes = part.provider().toByteArray()
            if (bytes.isNotEmpty()) {
              photo =
                  YgdkPhotoUpload(
                      bytes = bytes,
                      fileName = part.originalFileName ?: "upload.jpg",
                      mimeType = part.contentType?.toString() ?: "application/octet-stream",
                  )
            }
          }
      else -> Unit
    }
    part.dispose()
  }

  return YgdkClockinSubmitRequest(
      itemId = itemId,
      startTime = startTime,
      endTime = endTime,
      place = place,
      shareToSquare = shareToSquare,
      photo = photo,
  )
}

private suspend fun ApplicationCall.respondYgdkError(e: YgdkException) {
  val status =
      when (e.code) {
        "invalid_request" -> HttpStatusCode.BadRequest
        "unauthenticated" -> HttpStatusCode.Unauthorized
        else -> HttpStatusCode.BadGateway
      }
  respond(status, ErrorResponse(ErrorDetails(e.code, e.message ?: "阳光打卡请求失败")))
}
