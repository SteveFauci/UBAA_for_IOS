package cn.edu.ubaa.ygdk

import cn.edu.ubaa.model.dto.YgdkClockinSubmitRequest
import cn.edu.ubaa.model.dto.YgdkClockinSubmitResponse
import cn.edu.ubaa.model.dto.YgdkItemDto
import cn.edu.ubaa.model.dto.YgdkOverviewResponse
import cn.edu.ubaa.model.dto.YgdkRecordDto
import cn.edu.ubaa.model.dto.YgdkRecordsPageResponse
import cn.edu.ubaa.model.dto.YgdkTermSummaryDto
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.random.Random

private const val YGDK_ZONE_ID = "Asia/Shanghai"
private val YGDK_DATETIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val YGDK_ISO_DATETIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

internal data class YgdkGeneratedImage(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
)

internal open class YgdkImageGenerator(
    private val fileNameProvider: () -> String = {
      "ygdk_auto_${System.currentTimeMillis()}.png"
    },
) {
  open fun generate(): YgdkGeneratedImage {
    val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB)

    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return YgdkGeneratedImage(
        bytes = output.toByteArray(),
        fileName = fileNameProvider(),
        mimeType = "image/png",
    )
  }
}

internal class YgdkService(
    private val clientProvider: (String) -> YgdkClient = ::YgdkClient,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now(ZoneId.of(YGDK_ZONE_ID)) },
    private val random: Random = Random.Default,
    private val imageGenerator: YgdkImageGenerator = YgdkImageGenerator(),
) {
  private data class CachedClient(
      val client: YgdkClient,
      @Volatile var lastAccessAt: Long,
  )

  private data class ResolvedContext(
      val classify: YgdkClassifyRaw,
      val items: List<YgdkItemRaw>,
      val defaultItem: YgdkItemRaw,
      val summary: YgdkTermSummaryDto,
  )

  private val clientCache = ConcurrentHashMap<String, CachedClient>()

  suspend fun getOverview(username: String): YgdkOverviewResponse {
    val context = resolveContext(username)
    return YgdkOverviewResponse(
        summary = context.summary,
        classifyId = context.classify.classifyId,
        classifyName = context.classify.name,
        defaultItemId = context.defaultItem.itemId,
        defaultItemName = context.defaultItem.name,
        items = context.items.map { it.toDto() },
    )
  }

  suspend fun getRecords(username: String, page: Int, size: Int): YgdkRecordsPageResponse {
    if (page <= 0 || size <= 0) throw YgdkException("分页参数无效", code = "invalid_request")
    val context = resolveContext(username)
    val records = withFreshClientRetry(username) { client ->
      client.getRecords(context.classify.classifyId, page, size)
    }
    val itemMap = context.items.associateBy { it.itemId }
    return YgdkRecordsPageResponse(
        content = records.records.map { it.toDto(itemMap) },
        total = records.total,
        page = page,
        size = size,
        hasMore = page * size < records.total,
    )
  }

  suspend fun submitClockin(
      username: String,
      request: YgdkClockinSubmitRequest,
  ): YgdkClockinSubmitResponse {
    val context = resolveContext(username)
    val item =
        request.itemId?.let { itemId ->
          context.items.firstOrNull { it.itemId == itemId }
              ?: throw YgdkException("所选运动项目不存在", code = "invalid_request")
        } ?: context.defaultItem

    val (startAt, endAt) = resolveTimeRange(request.startTime, request.endTime)
    val place = request.place?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_PLACE
    val shareToSquare = request.shareToSquare ?: false
    val upload =
        request.photo?.let {
          YgdkGeneratedImage(bytes = it.bytes, fileName = it.fileName, mimeType = it.mimeType)
        } ?: imageGenerator.generate()

    val uploadedFile =
        withFreshClientRetry(username) { client ->
          client.uploadImage(
              bytes = upload.bytes,
              fileName = upload.fileName,
              mimeType = upload.mimeType,
          )
        }
    val result =
        withFreshClientRetry(username) { client ->
          client.clockin(
              classifyId = context.classify.classifyId,
              itemId = item.itemId,
              itemName = item.name,
              startAt = startAt,
              endAt = endAt,
              place = place,
              imageName = uploadedFile.fileName,
              isOpen = shareToSquare,
          )
        }

    return YgdkClockinSubmitResponse(
        success = true,
        message = "打卡成功",
        recordId = result.recordId,
        summary = mergeSubmitSummary(context.summary, result),
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

  private suspend fun resolveContext(username: String): ResolvedContext {
    return withFreshClientRetry(username) { client ->
      val classify = resolveSportsClassify(client.getClassifyList())
      val items = client.getItemList(classify.classifyId)
      val defaultItem = resolveDefaultItem(items)
      val count = runCatching { client.getCheckCount(classify.classifyId) }.getOrNull()
      val term = runCatching { client.getTerm() }.getOrNull()
      ResolvedContext(
          classify = classify,
          items = items,
          defaultItem = defaultItem,
          summary = mapSummary(classify, count, term),
      )
    }
  }

  private fun getClient(username: String): YgdkClient {
    val now = System.currentTimeMillis()
    return clientCache
        .compute(username) { _, existing ->
          existing?.also { it.lastAccessAt = now } ?: CachedClient(clientProvider(username), now)
        }!!
        .client
  }

  private suspend fun <T> withFreshClientRetry(
      username: String,
      block: suspend (YgdkClient) -> T,
  ): T {
    return try {
      block(getClient(username))
    } catch (e: YgdkAuthenticationException) {
      discardClient(username)
      block(getClient(username))
    }
  }

  private fun discardClient(username: String) {
    clientCache.remove(username)?.client?.close()
  }

  private fun resolveSportsClassify(classifies: List<YgdkClassifyRaw>): YgdkClassifyRaw {
    if (classifies.isEmpty()) throw YgdkException("未获取到阳光打卡分类")
    return classifies.firstOrNull { it.name.contains("体育") }
        ?: classifies.firstOrNull { it.classifyId == 1 }
        ?: classifies.first()
  }

  private fun resolveDefaultItem(items: List<YgdkItemRaw>): YgdkItemRaw {
    if (items.isEmpty()) throw YgdkException("未获取到阳光打卡项目列表")
    return items.firstOrNull { it.name.contains("跑") }
        ?: items.sortedBy { it.sort ?: Int.MAX_VALUE }.first()
  }

  private fun mapSummary(
      classify: YgdkClassifyRaw,
      count: YgdkCountRaw?,
      term: YgdkTermRaw?,
  ): YgdkTermSummaryDto {
    return YgdkTermSummaryDto(
        termId = term?.termId ?: term?.id,
        termName = term?.name,
        termCount = count?.termCountShow ?: count?.termCount ?: 0,
        termTarget = count?.termNum ?: classify.termNum,
        weekCount = count?.weekCount,
        weekTarget = count?.weekNum ?: classify.weekNum,
        monthCount = count?.monthCount,
        monthTarget = count?.monthNum ?: classify.monthNum,
        dayCount = count?.dayCount,
        goodCount = count?.termGoodCountShow ?: count?.termGoodCount,
    )
  }

  private fun mergeSubmitSummary(
      existing: YgdkTermSummaryDto,
      result: YgdkClockinResultRaw,
  ): YgdkTermSummaryDto {
    return existing.copy(
        termId = result.termId ?: existing.termId,
        termCount = result.termCountShow ?: result.termCount ?: existing.termCount,
        termTarget = result.termNum ?: existing.termTarget,
        weekCount = result.weekCount ?: existing.weekCount,
        weekTarget = result.weekNum ?: existing.weekTarget,
        monthCount = result.monthCount ?: existing.monthCount,
        monthTarget = result.monthNum ?: existing.monthTarget,
        dayCount = result.dayCount ?: existing.dayCount,
        goodCount = result.termGoodCountShow ?: result.termGoodCount ?: existing.goodCount,
    )
  }

  private fun resolveTimeRange(
      startTime: String?,
      endTime: String?,
  ): Pair<LocalDateTime, LocalDateTime> {
    val normalizedStart = startTime?.trim().orEmpty()
    val normalizedEnd = endTime?.trim().orEmpty()
    if ((normalizedStart.isBlank() && normalizedEnd.isNotBlank()) ||
        (normalizedStart.isNotBlank() && normalizedEnd.isBlank())) {
      throw YgdkException("开始时间和结束时间需要同时填写", code = "invalid_request")
    }
    if (normalizedStart.isBlank() && normalizedEnd.isBlank()) {
      return generateDefaultTimeRange()
    }

    val startAt = parseDateTime(normalizedStart)
    val endAt = parseDateTime(normalizedEnd)
    if (!endAt.isAfter(startAt)) {
      throw YgdkException("结束时间必须晚于开始时间", code = "invalid_request")
    }
    if (startAt.toLocalDate() != endAt.toLocalDate()) {
      throw YgdkException("当前仅支持同一天内的一小时打卡", code = "invalid_request")
    }
    return startAt to endAt
  }

  private fun parseDateTime(value: String): LocalDateTime {
    return runCatching { LocalDateTime.parse(value, YGDK_DATETIME_FORMATTER) }
        .recoverCatching { LocalDateTime.parse(value, YGDK_ISO_DATETIME_FORMATTER) }
        .getOrElse { throw YgdkException("时间格式错误，请使用 yyyy-MM-dd HH:mm", code = "invalid_request") }
  }

  private fun generateDefaultTimeRange(): Pair<LocalDateTime, LocalDateTime> {
    val now = nowProvider()
    val candidateStarts =
        buildList {
          for (offset in 0 until DEFAULT_RANDOM_DAY_RANGE_DAYS) {
            val date = now.toLocalDate().minusDays(offset.toLong())
            val dayStart = LocalDateTime.of(date, LocalTime.of(8, 0))
            val latestEnd =
                if (offset == 0) {
                  minOf(now, LocalDateTime.of(date, LocalTime.of(22, 0)))
                } else {
                  LocalDateTime.of(date, LocalTime.of(22, 0))
                }
            val latestStart = latestEnd.minusHours(1)
            if (latestStart.isBefore(dayStart)) continue

            var start = dayStart
            while (!start.isAfter(latestStart)) {
              add(start)
              start = start.plusHours(1)
            }
          }
        }
    if (candidateStarts.isEmpty()) {
      val fallbackStart = LocalDateTime.of(now.toLocalDate(), LocalTime.of(8, 0))
      return fallbackStart to fallbackStart.plusHours(1)
    }
    val selectedStart = candidateStarts[random.nextInt(candidateStarts.size)]
    return selectedStart to selectedStart.plusHours(1)
  }

  private fun YgdkItemRaw.toDto(): YgdkItemDto =
      YgdkItemDto(itemId = itemId, name = name, type = type, sort = sort)

  private fun YgdkRecordRaw.toDto(itemMap: Map<Int, YgdkItemRaw>): YgdkRecordDto {
    return YgdkRecordDto(
        recordId = recordId,
        itemId = itemId,
        itemName = itemName ?: itemId?.let { itemMap[it]?.name },
        startTime = YgdkClient.timestampToDateTimeText(startTime),
        endTime = YgdkClient.timestampToDateTimeText(endTime),
        place = place,
        images = images,
        isOpen = isOpen,
        state = state,
        createdAt = createTimeLabel,
        createdAtLabel = createTimeLabel,
    )
  }

  companion object {
    private const val DEFAULT_MAX_IDLE_MILLIS = 30 * 60 * 1000L
    private const val DEFAULT_PLACE = "操场"
    private const val DEFAULT_RANDOM_DAY_RANGE_DAYS = 3
  }
}

internal object GlobalYgdkService {
  val instance: YgdkService by lazy { YgdkService() }
}
