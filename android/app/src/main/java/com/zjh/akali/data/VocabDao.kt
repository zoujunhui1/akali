package com.zjh.akali.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabDao {
    @Query("SELECT * FROM vocab_lessons ORDER BY unit, number")
    fun observeAll(): Flow<List<VocabLesson>>

    @Query("SELECT * FROM vocab_lessons WHERE code = :code")
    fun observe(code: String): Flow<VocabLesson?>

    @Query("SELECT * FROM vocab_lessons WHERE code = :code")
    suspend fun find(code: String): VocabLesson?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lesson: VocabLesson)

    @Query("UPDATE vocab_lessons SET downloaded = :downloaded WHERE code = :code")
    suspend fun setDownloaded(code: String, downloaded: Boolean)

    @Query("UPDATE vocab_lessons SET notes = :notes WHERE code = :code")
    suspend fun setNotes(code: String, notes: String?)

    @Query("DELETE FROM vocab_lessons WHERE code = :code")
    suspend fun delete(code: String)

    @Query("SELECT COUNT(*) FROM vocab_lessons")
    suspend fun count(): Int
}
