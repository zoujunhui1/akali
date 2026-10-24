package com.zjh.akali.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Lesson::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessonDao(): LessonDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN timestamps TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN vocab TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN remoteAudioSha TEXT")
                db.execSQL("ALTER TABLE lessons ADD COLUMN remoteAudioSize INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context, name: String? = "akali.db"): AppDatabase =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
            }
    }
}
