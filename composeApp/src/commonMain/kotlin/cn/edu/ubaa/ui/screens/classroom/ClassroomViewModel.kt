package cn.edu.ubaa.ui.screens.classroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.ubaa.api.ClassroomApi
import cn.edu.ubaa.model.dto.ClassroomInfo
import cn.edu.ubaa.model.dto.ClassroomQueryResponse
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 教室查询 UI 状态密封类。 */
sealed class ClassroomUiState {
  object Idle : ClassroomUiState()

  object Loading : ClassroomUiState()

  data class Success(val data: ClassroomQueryResponse) : ClassroomUiState()

  data class Error(val message: String) : ClassroomUiState()
}

/** 教室查询模块的 ViewModel。 负责校区选择、日期选择以及在结果中进行模糊搜索过滤。 */
class ClassroomViewModel(private val api: ClassroomApi = ClassroomApi()) : ViewModel() {
  private val _uiState = MutableStateFlow<ClassroomUiState>(ClassroomUiState.Idle)
  /** 核心查询状态流。 */
  val uiState: StateFlow<ClassroomUiState> = _uiState.asStateFlow()

  private val _xqid = MutableStateFlow(1) // 1: 学院路, 2: 沙河, 3: 杭州
  /** 当前选中的校区 ID。 */
  val xqid: StateFlow<Int> = _xqid.asStateFlow()

  private val _date = MutableStateFlow(getCurrentDate())
  /** 当前选中的查询日期。 */
  val date: StateFlow<String> = _date.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  /** 当前的搜索关键字流。 */
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _selectedBuilding = MutableStateFlow<String?>(null)
  /** 当前选中的楼栋。null 表示展示全部楼栋。 */
  val selectedBuilding: StateFlow<String?> = _selectedBuilding.asStateFlow()

  /** 当前查询结果中的可选楼栋列表。 */
  val availableBuildings: StateFlow<List<String>> =
    _uiState
      .map { state ->
        if (state is ClassroomUiState.Success) {
          state.data.d.list.keys.toList()
        } else {
          emptyList()
        }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /** 根据楼栋与搜索关键字过滤后的教室数据流。 */
  val filteredData: StateFlow<Map<String, List<ClassroomInfo>>> =
    combine(_uiState, _selectedBuilding, _searchQuery) { state, selectedBuilding, query ->
        if (state is ClassroomUiState.Success) {
          val allData = state.data.d.list
          val buildingFilteredData =
            selectedBuilding?.let { building ->
              allData[building]?.let { listOfClassroom -> mapOf(building to listOfClassroom) }
                ?: emptyMap()
            } ?: allData
          if (query.isBlank()) {
            buildingFilteredData
          } else {
            buildingFilteredData
              .mapValues { (_, list) -> list.filter { it.name.contains(query, true) } }
              .filter { (building, list) -> building.contains(query, true) || list.isNotEmpty() }
          }
        } else emptyMap()
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  /** 更新搜索关键字。 */
  fun setSearchQuery(query: String) {
    _searchQuery.value = query
  }

  /** 切换楼栋筛选；再次点击当前楼栋会恢复到全部楼栋。 */
  fun toggleBuilding(building: String) {
    _selectedBuilding.value = if (_selectedBuilding.value == building) null else building
  }

  /** 切换校区并自动重新查询。 */
  fun setXqid(id: Int) {
    _xqid.value = id
    _selectedBuilding.value = null
    query()
  }

  /** 切换日期并自动重新查询。 */
  fun setDate(date: String) {
    _date.value = date
    _selectedBuilding.value = null
    query()
  }

  /** 执行查询动作。 */
  fun query() {
    viewModelScope.launch {
      _uiState.value = ClassroomUiState.Loading
      api
        .queryClassrooms(_xqid.value, _date.value)
        .onSuccess {
          _uiState.value = ClassroomUiState.Success(it)
          if (_selectedBuilding.value !in it.d.list.keys) {
            _selectedBuilding.value = null
          }
        }
        .onFailure { _uiState.value = ClassroomUiState.Error(it.message ?: "未知错误") }
    }
  }

  @OptIn(ExperimentalTime::class)
  private fun getCurrentDate(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.year}-${now.monthNumber.toString().padStart(2, '0')}-${now.dayOfMonth.toString().padStart(2, '0')}"
  }
}
