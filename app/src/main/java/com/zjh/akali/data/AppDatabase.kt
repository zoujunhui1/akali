package com.zjh.akali.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Lesson::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessonDao(): LessonDao

    companion object {
        fun create(context: Context, name: String? = "akali.db"): AppDatabase =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            }
    }
}
