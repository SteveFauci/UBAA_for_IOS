package cn.edu.ubaa.ui.common.util

import androidx.compose.runtime.Composable

data class PickedImage(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
) {
  val sizeInBytes: Int
    get() = bytes.size
}

interface PlatformImagePicker {
  val canCapturePhoto: Boolean

  fun pickImage()

  fun capturePhoto()
}

@Composable
expect fun rememberPlatformImagePicker(
    onImagePicked: (PickedImage) -> Unit,
    onError: (String) -> Unit,
): PlatformImagePicker

fun formatImageSize(bytes: Int): String {
  if (bytes < 1024) return "${bytes}B"
  if (bytes < 1024 * 1024) return "${bytes / 1024}KB"
  return String.format("%.1fMB", bytes / 1024f / 1024f)
}
