package cn.edu.ubaa.spoc

import cn.edu.ubaa.model.dto.SpocSubmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class SpocSupportTest {
  private val json = Json

  @Test
  fun `extract login tokens from cas redirect url`() {
    val tokens =
      SpocParsers.extractLoginTokens(
        "https://spoc.buaa.edu.cn/spocnew/cas?token=test-token&refreshToken=test-refresh"
      )

    assertEquals("test-token", tokens?.token)
    assertEquals("test-refresh", tokens?.refreshToken)
  }

  @Test
  fun `extract login tokens returns null for unrelated url`() {
    val tokens = SpocParsers.extractLoginTokens("https://spoc.buaa.edu.cn/spocnewht/index")

    assertNull(tokens)
  }

  @Test
  fun `resolve role code prefers jsdm then rolecode then jsdmList`() {
    val fromJsdm =
      SpocCasLoginContent(
        jsdm = "01",
        rolecode = json.parseToJsonElement("""["02"]"""),
        jsdmList = json.parseToJsonElement("""["03"]"""),
      )
    val fromRoleCode =
      SpocCasLoginContent(
        jsdm = null,
        rolecode = json.parseToJsonElement("""["02"]"""),
        jsdmList = json.parseToJsonElement("""["03"]"""),
      )
    val fromJsdmList =
      SpocCasLoginContent(
        jsdm = null,
        rolecode = null,
        jsdmList = json.parseToJsonElement("""["03"]"""),
      )

    assertEquals("01", SpocParsers.resolveRoleCode(fromJsdm))
    assertEquals("02", SpocParsers.resolveRoleCode(fromRoleCode))
    assertEquals("03", SpocParsers.resolveRoleCode(fromJsdmList))
  }

  @Test
  fun `map submission status handles known and unknown cases`() {
    assertEquals(
      SpocSubmissionStatus.UNSUBMITTED,
      SpocParsers.mapSubmissionStatus(rawStatus = null, hasContent = false),
    )
    assertEquals(
      SpocSubmissionStatus.SUBMITTED,
      SpocParsers.mapSubmissionStatus(rawStatus = "1", hasContent = true),
    )
    assertEquals(
      SpocSubmissionStatus.UNSUBMITTED,
      SpocParsers.mapSubmissionStatus(rawStatus = "0", hasContent = true),
    )
    assertEquals(
      SpocSubmissionStatus.UNKNOWN,
      SpocParsers.mapSubmissionStatus(rawStatus = "9", hasContent = true),
    )
  }

  @Test
  fun `html sanitizer returns readable plain text`() {
    val plainText =
      SpocParsers.toPlainText(
        "<h4>&nbsp;Lab1</h4>\n<p>- Solve a real-world problem.</p><p>- Parallelize it in R/Python.</p>"
      )

    assertEquals("Lab1 - Solve a real-world problem. - Parallelize it in R/Python.", plainText)
  }
}
