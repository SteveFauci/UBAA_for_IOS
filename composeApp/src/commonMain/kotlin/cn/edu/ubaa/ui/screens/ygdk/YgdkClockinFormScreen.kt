package cn.edu.ubaa.ui.screens.ygdk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.ubaa.model.dto.YgdkItemDto
import cn.edu.ubaa.ui.common.util.PlatformImagePicker
import cn.edu.ubaa.ui.common.util.formatImageSize

@Composable
fun YgdkClockinFormScreen(
    uiState: YgdkUiState,
    imagePicker: PlatformImagePicker,
    onItemSelected: (Int?) -> Unit,
    onStartTimeChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onPlaceChange: (String) -> Unit,
    onShareChange: (Boolean) -> Unit,
    onClearPhoto: () -> Unit,
    onSubmit: () -> Unit,
) {
  var showItemDialog by remember { mutableStateOf(false) }

  if (showItemDialog) {
    YgdkItemSelectorDialog(
        items = uiState.overview?.items.orEmpty(),
        selectedItemId = uiState.form.itemId,
        onDismiss = { showItemDialog = false },
        onSelect = {
          onItemSelected(it)
          showItemDialog = false
        },
    )
  }

  Scaffold(
      bottomBar = {
        Button(
            onClick = onSubmit,
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
          Text(if (uiState.isSubmitting) "提交中..." else "提交打卡")
        }
      }
  ) { padding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Card(
          colors =
              CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
            text = YGDK_FORM_HINT,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
      }

      uiState.submitMessage?.let {
        Card(
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
              text = it,
              modifier = Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(text = "运动项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          OutlinedButton(onClick = { showItemDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(uiState.selectedItemLabel())
          }
          Text(
              text = "留空则默认使用 ${uiState.overview?.defaultItemName ?: "跑步"}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(text = "时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          OutlinedTextField(
              value = uiState.form.startTime,
              onValueChange = onStartTimeChange,
              label = { Text("开始时间") },
              placeholder = { Text("2026-04-01 08:00") },
              modifier = Modifier.fillMaxWidth(),
              leadingIcon = { Icon(Icons.Default.AccessTime, null) },
              singleLine = true,
          )
          OutlinedTextField(
              value = uiState.form.endTime,
              onValueChange = onEndTimeChange,
              label = { Text("结束时间") },
              placeholder = { Text("2026-04-01 09:00") },
              modifier = Modifier.fillMaxWidth(),
              leadingIcon = { Icon(Icons.Default.AccessTime, null) },
              singleLine = true,
          )
          Text(
              text = "开始时间和结束时间需要同时填写；留空则自动生成最近三天内 08:00 到当日上限时间之间的一小时记录。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(text = "地点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          OutlinedTextField(
              value = uiState.form.place,
              onValueChange = onPlaceChange,
              label = { Text("运动地点") },
              placeholder = { Text("操场") },
              modifier = Modifier.fillMaxWidth(),
              leadingIcon = { Icon(Icons.Default.Place, null) },
              singleLine = true,
          )
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(text = "照片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = imagePicker::pickImage, modifier = Modifier.weight(1f)) {
              Icon(Icons.Default.Image, contentDescription = null)
              Spacer(modifier = Modifier.padding(4.dp))
              Text("选择图片")
            }
            if (imagePicker.canCapturePhoto) {
              OutlinedButton(onClick = imagePicker::capturePhoto, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("拍摄照片")
              }
            }
          }
          uiState.form.photo?.let { photo ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(text = photo.fileName, fontWeight = FontWeight.Bold)
                  Text(
                      text = "${photo.mimeType} · ${formatImageSize(photo.sizeInBytes)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(onClick = onClearPhoto) {
                  Icon(Icons.Default.Delete, contentDescription = "清除图片")
                }
              }
            }
          } ?: Text(
              text = "未选择图片时会自动生成全透明图片。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { onShareChange(!uiState.form.shareToSquare) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Default.Share, contentDescription = null)
          Spacer(modifier = Modifier.padding(6.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(text = "分享到广场", fontWeight = FontWeight.Bold)
            Text(
                text = "默认不分享，开启后会把本次打卡同步到广场。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Checkbox(checked = uiState.form.shareToSquare, onCheckedChange = onShareChange)
        }
      }
    }
  }
}

@Composable
private fun YgdkItemSelectorDialog(
    items: List<YgdkItemDto>,
    selectedItemId: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("选择运动项目") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("不设置，交给系统自动选择")
          }
          items.forEach { item ->
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable { onSelect(item.itemId) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                  checked = item.itemId == selectedItemId,
                  onCheckedChange = { onSelect(item.itemId) },
              )
              Text(item.name)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
  )
}

private fun YgdkUiState.selectedItemLabel(): String {
  val itemId = form.itemId ?: return "不设置，使用默认运动"
  return overview?.items?.firstOrNull { it.itemId == itemId }?.name ?: "不设置，使用默认运动"
}
