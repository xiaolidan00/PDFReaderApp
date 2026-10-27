package com.xld.pdfreader.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.UUID

class PdfRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).pdfDocumentDao()

    fun observeAll() = dao.observeAll()

    suspend fun getDocument(id: String) = dao.getById(id)

    /**
     * 导入 SAF 选择的 PDF：持久化授权、按 uri 去重、写入记录并回填总页数。
     */
    suspend fun importDocuments(uris: List<Uri>): ImportResult {
        var added = 0
        var skipped = 0
        for (uri in uris) {
            try {
                takePersistablePermission(uri)
                if (dao.getByUri(uri.toString()) != null) {
                    skipped++
                    continue
                }
                val meta = queryMeta(uri) ?: run { skipped++; continue }
                val pageCount = pageCountOf(uri) ?: 0
                dao.insertAll(
                    listOf(
                        PdfDocumentEntity(
                            id = UUID.randomUUID().toString(),
                            uri = uri.toString(),
                            displayName = meta.first,
                            sizeBytes = meta.second,
                            addedAt = System.currentTimeMillis(),
                            pageCount = pageCount
                        )
                    )
                )
                added++
            } catch (_: Exception) {
                skipped++
            }
        }
        return ImportResult(added, skipped)
    }

    suspend fun updateProgress(id: String, page: Int) {
        dao.updateProgress(id, page, System.currentTimeMillis())
    }

    suspend fun updatePageCount(id: String, pageCount: Int) {
        dao.updatePageCount(id, pageCount)
    }

    suspend fun markBroken(id: String, broken: Boolean) {
        dao.updateBroken(id, broken)
    }

    /** 仅删除阅读记录，不触碰文件。 */
    suspend fun deleteRecords(ids: List<String>) {
        dao.deleteByIds(ids)
    }

    /** 删除存储地址对应的文件；失败的记录保留并标记 isBroken。 */
    suspend fun deleteFiles(ids: List<String>): DeleteFilesResult {
        var deleted = 0
        val failed = mutableListOf<String>()
        for (id in ids) {
            val doc = dao.getById(id) ?: continue
            val ok = try {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(doc.uri))
            } catch (_: Exception) {
                false
            }
            if (ok) {
                releasePersistablePermission(Uri.parse(doc.uri))
                dao.deleteByIds(listOf(id))
                deleted++
            } else {
                dao.updateBroken(id, true)
                failed.add(doc.displayName)
            }
        }
        return DeleteFilesResult(deleted, failed)
    }

    /** 为失效记录重新指定文件（换绑 URI）。 */
    suspend fun relocateDocument(id: String, uri: Uri): Boolean {
        val doc = dao.getById(id) ?: return false
        return try {
            takePersistablePermission(uri)
            val meta = queryMeta(uri) ?: return false
            dao.update(
                doc.copy(
                    uri = uri.toString(),
                    displayName = meta.first,
                    sizeBytes = meta.second,
                    isBroken = false
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun takePersistablePermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    private fun releasePersistablePermission(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // 权限可能已不存在，忽略
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long>? {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment ?: return null
        }
        return name to size.coerceAtLeast(0L)
    }

    private fun pageCountOf(uri: Uri): Int? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        } catch (_: Exception) {
            null
        }
    }

    data class ImportResult(val added: Int, val skipped: Int)

    data class DeleteFilesResult(val deleted: Int, val failed: List<String>)
}
