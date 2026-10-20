package com.zjh.akali.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons")
    fun observeAll(): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons WHERE level = :level AND number = :number LIMIT 1")
    suspend fun find(level: String, number: Int): Lesson?

    @Query("SELECT * FROM lessons WHERE id = :id")
    suspend fun get(id: Long): Lesson?

    @Query("SELECT * FROM lessons WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<Lesson>

    /** 冲突（同级别同编号）时返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(lesson: Lesson): Long

    @Query("UPDATE lessons SET audioDownloaded = :downloaded WHERE id = :id")
    suspend fun setAudioDownloaded(id: Long, downloaded: Boolean)

    @Query("UPDATE lessons SET sourceUrl = :url WHERE id = :id")
    suspend fun setSourceUrl(id: Long, url: String)

    @Query("DELETE FROM lessons WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
