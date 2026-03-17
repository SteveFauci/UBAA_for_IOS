package cn.edu.ubaa.spoc

import cn.edu.ubaa.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpocRoutesTest {
  @Test
  fun `GET assignments without token returns unauthorized`() = testApplication {
    application { module() }

    val response = client.get("/api/v1/spoc/assignments")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertTrue(response.bodyAsText().contains("invalid_token"))
  }
}
