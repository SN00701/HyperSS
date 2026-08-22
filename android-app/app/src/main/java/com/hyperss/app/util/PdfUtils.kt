package com.hyperss.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF 生成 / 预览 / 导出 / 读取工具。
 *
 * 导出目标为公共下载目录 Download/HyperSSDL（经 MediaStore.Downloads 插入，
 * 系统自动创建文件夹；本应用创建的文件读写均无需存储权限）。
 */
object PdfUtils {

    /** A4 纵向（PostScript 点）。 */
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    private const val MARGIN = 24f
    private const val GAP = 12f

    /** 阅读器里的 PDF 条目。 */
    data class PdfItem(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /** 按最大边长受限解码图片，避免超长截图整图解码 OOM。 */
    fun decodeBounded(path: String, maxDim: Int = 1600): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 把单张图片按比例缩放后居中绘制到指定单元格。 */
    private fun drawFit(canvas: Canvas, bmp: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        if (bmp.width <= 0 || bmp.height <= 0 || w <= 0 || h <= 0) return
        val scale = minOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = x + (w - dw) / 2f
        val top = y + (h - dh) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
    }

    /**
     * 把若干图片绘制到一页：
     * - 1 张：整页；
     * - 2 张：左右两列（整页高，适合长截图）；
     * - 3 张：2×2 田字格，第 3 张在底行居中占整行；
     * - 4 张：2×2 田字格。
     * 图片均按比例缩放并居中于各自单元格。
     * PDF 生成与布局预览共用此函数，保证所见即所得。
     */
    private fun drawPage(canvas: Canvas, images: List<Bitmap>) {
        canvas.drawColor(Color.WHITE)
        if (images.isEmpty()) return
        val contentW = PAGE_W - 2 * MARGIN
        val contentH = PAGE_H - 2 * MARGIN
        when (images.size) {
            1 -> drawFit(canvas, images[0], MARGIN, MARGIN, contentW, contentH)
            2 -> {
                val cw = (contentW - GAP) / 2f
                drawFit(canvas, images[0], MARGIN, MARGIN, cw, contentH)
                drawFit(canvas, images[1], MARGIN + cw + GAP, MARGIN, cw, contentH)
            }
            else -> {
                // 2×2 田字格
                val cw = (contentW - GAP) / 2f
                val ch = (contentH - GAP) / 2f
                drawFit(canvas, images[0], MARGIN, MARGIN, cw, ch)
                drawFit(canvas, images[1], MARGIN + cw + GAP, MARGIN, cw, ch)
                if (images.size >= 4) {
                    drawFit(canvas, images[2], MARGIN, MARGIN + ch + GAP, cw, ch)
                    drawFit(canvas, images[3], MARGIN + cw + GAP, MARGIN + ch + GAP, cw, ch)
                } else {
                    // 恰好 3 张：第 3 张在底行居中占整行
                    drawFit(canvas, images[2], MARGIN, MARGIN + ch + GAP, contentW, ch)
                }
            }
        }
    }

    /** 生成 PDF 字节：images 按每页 perPage 张（1~4）依序列布局。 */
    fun buildPdf(images: List<Bitmap>, perPage: Int): ByteArray? {
        if (images.isEmpty()) return null
        val per = perPage.coerceIn(1, 4)
        val doc = PdfDocument()
        try {
            var index = 0
            var pageNo = 1
            while (index < images.size) {
                val pageImages = images.subList(index, minOf(index + per, images.size))
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), pageNo).create()
                )
                drawPage(page.canvas, pageImages)
                doc.finishPage(page)
                index += per
                pageNo++
            }
            val out = java.io.ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } catch (e: Throwable) {
            return null
        } finally {
            doc.close()
        }
    }

    /** 渲染布局预览（与最终 PDF 同布局），返回每页一张位图。 */
    fun buildPreview(images: List<Bitmap>, perPage: Int): List<Bitmap> {
        if (images.isEmpty()) return emptyList()
        val per = perPage.coerceIn(1, 4)
        val scale = 2f // 预览分辨率倍数
        val pages = ArrayList<Bitmap>()
        var index = 0
        while (index < images.size) {
            val pageImages = images.subList(index, minOf(index + per, images.size))
            val bmp = Bitmap.createBitmap(
                (PAGE_W * scale).toInt(), (PAGE_H * scale).toInt(), Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            drawPage(canvas, pageImages)
            pages.add(bmp)
            index += per
        }
        return pages
    }

    /** 导出到公共 Download/HyperSSDL 目录，返回文件 Uri（失败返回 null）。 */
    fun exportToDownloads(context: Context, bytes: ByteArray, displayName: String): Uri? {
        if (bytes.isEmpty()) return null
        val name = if (displayName.endsWith(".pdf")) displayName else "$displayName.pdf"
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HyperSSDL")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            uri
        } catch (e: Throwable) {
            null
        }
    }

    /** 列出 Download/HyperSSDL 下的全部 PDF（按修改时间倒序）。 */
    fun listPdfs(context: Context): List<PdfItem> {
        return try {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
            )
            val list = ArrayList<PdfItem>()
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.MIME_TYPE} = ?",
                arrayOf("%Download/HyperSSDL%", "application/pdf"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString(),
                    )
                    list.add(
                        PdfItem(
                            uri = uri,
                            name = cursor.getString(nameCol) ?: "未命名.pdf",
                            sizeBytes = cursor.getLong(sizeCol),
                            lastModified = cursor.getLong(dateCol) * 1000L,
                        )
                    )
                }
            }
            list
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** 重命名（更新 MediaStore 显示名，保持 .pdf 后缀）。 */
    fun renamePdf(context: Context, item: PdfItem, newName: String): Boolean {
        if (newName.isBlank()) return false
        val name = if (newName.endsWith(".pdf")) newName else "$newName.pdf"
        return try {
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name) }
            context.contentResolver.update(item.uri, values, null, null) > 0
        } catch (e: Throwable) {
            false
        }
    }

    /** 另存副本（读取原文件字节后以新名称插入同目录），返回新文件 Uri。 */
    fun saveCopy(context: Context, item: PdfItem, newName: String): Uri? {
        return try {
            val bytes = context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() } ?: return null
            exportToDownloads(context, bytes, newName)
        } catch (e: Throwable) {
            null
        }
    }

    /** 渲染 PDF 全部页面为位图（阅读器用）。 */
    fun renderPdfPages(context: Context, uri: Uri, maxPageWidth: Int = 1080): List<Bitmap> {
        val pages = ArrayList<Bitmap>()
        try {
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val scale = maxPageWidth.toFloat() / page.width
                            val bmp = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages.add(bmp)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // 读取失败按空处理
        }
        return pages
    }

    /** 从外部 Uri（SAF 选择器）导入 PDF 副本到 Download/HyperSSDL。 */
    fun importPdf(context: Context, source: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            // 读取原始文件名（无则用时间戳默认名）
            var name: String? = null
            resolver.query(source, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
            val bytes = resolver.openInputStream(source)?.use { it.readBytes() } ?: return false
            if (bytes.isEmpty()) return false
            // 校验 PDF 魔数
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'D'.code.toByte()) return false
            exportToDownloads(context, bytes, name ?: defaultPdfName()) != null
        } catch (e: Throwable) {
            false
        }
    }

    /** 默认导出文件名：HyperSS_日期_时间。 */
    fun defaultPdfName(): String =
        "HyperSS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
