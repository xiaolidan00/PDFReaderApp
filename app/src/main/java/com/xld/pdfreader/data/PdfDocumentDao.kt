package com.xld.pdfreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDocumentDao {

    @Query("SELECT * FROM pdf_documents")
    fun observeAll(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getById(id: String): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PdfDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(documents: List<PdfDocumentEntity>)

    @Update
    suspend fun update(document: PdfDocumentEntity)

    @Query(
        "UPDATE pdf_documents SET currentPage = :page, lastOpenedAt = :at WHERE id = :id"
    )
    suspend fun updateProgress(id: String, page: Int, at: Long)

    @Query(
        "UPDATE pdf_documents SET pageCount = :pageCount WHERE id = :id AND pageCount != :pageCount"
    )
    suspend fun updatePageCount(id: String, pageCount: Int)

    @Query("UPDATE pdf_documents SET isBroken = :broken WHERE id = :id")
    suspend fun updateBroken(id: String, broken: Boolean)

    @Query("DELETE FROM pdf_documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
