package com.zjh.akali.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryMarkDao {
    @Query("SELECT * FROM entry_marks")
    fun observeAll(): Flow<List<EntryMark>>

    @Query("SELECT * FROM entry_marks WHERE code = :code AND entryKey = :key")
    suspend fun find(code: String, key: String): EntryMark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mark: EntryMark)

    @Query("SELECT * FROM entry_marks WHERE pending = 1")
    suspend fun pending(): List<EntryMark>

    /** 上传成功后才取消“待上传”；上传期间又改过的（状态变了）继续保持待上传。 */
    @Query("UPDATE entry_marks SET pending = 0 WHERE code = :code AND entryKey = :key AND state = :state")
    suspend fun markSynced(code: String, key: String, state: String)
}
