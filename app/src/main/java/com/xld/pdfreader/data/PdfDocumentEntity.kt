package com.xld.pdfreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PDF 阅读记录。文件本身不复制，仅持有 SAF content:// URI 引用。
 */
@Entity(tableName = "pdf_documents")
data class PdfDocumentEntity(
    @PrimaryKey val id: String,
    /** SAF content:// URI */
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    /** 导入时间 */
    val addedAt: Long,
    /** 最近阅读时间，0 表示从未阅读 */
    val lastOpenedAt: Long = 0L,
    /** 1-based 阅读进度 */
    val currentPage: Int = 1,
    /** 总页数，导入后回填，0 表示未知 */
    val pageCount: Int = 0,
    /** 原文件被移动/删除后置 true */
    val isBroken: Boolean = false
)
