package com.hyperss.app.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 按最大边长等比缩小解码图片，避免长图整体解码 OOM。
 */
fun decodeScaled(path: String, maxDim: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)
}

/**
 * [version] 变化（如原地编辑保存后）时强制重新解码，
 * 否则同路径覆盖写入后仍会显示旧图。
 */
@Composable
fun rememberScaledBitmap(path: String, maxDim: Int, version: Int = 0): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, key1 = path, key2 = version, key3 = maxDim) {
        value = withContext(Dispatchers.IO) {
            decodeScaled(path, maxDim)?.asImageBitmap()
        }
    }
    return state
}
