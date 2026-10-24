package com.zjh.akali.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Lesson::class, VocabLesson::class, EntryMark::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessonDao(): LessonDao
    abstract fun vocabDao(): VocabDao
    abstract fun markDao(): EntryMarkDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS vocab_lessons (code TEXT NOT NULL, unit INTEGER NOT NULL, number INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, pages TEXT NOT NULL, entries TEXT NOT NULL, bundleSha TEXT, bundleSize INTEGER NOT NULL, " +
                        "downloaded INTEGER NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(code))",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vocab_lessons ADD COLUMN notes TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS entry_marks (code TEXT NOT NULL, entryKey TEXT NOT NULL, state TEXT NOT NULL, " +
                        "pending INTEGER NOT NULL, PRIMARY KEY(code, entryKey))",
                )
            }
        }

        fun create(context: Context, name: String? = "akali.db"): AppDatabase =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
            }
    }
}
