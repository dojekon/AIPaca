package com.aipaca.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aipaca.android.data.db.dao.BenchResultDao
import com.aipaca.android.data.db.dao.BookmarkDao
import com.aipaca.android.data.db.dao.ChatDao
import com.aipaca.android.data.db.dao.DownloadDao
import com.aipaca.android.data.db.dao.DownloadPartDao
import com.aipaca.android.data.db.dao.MessageDao
import com.aipaca.android.data.db.dao.ModelDao
import com.aipaca.android.data.db.dao.ProfileCacheDao
import com.aipaca.android.data.db.entity.BenchResultEntity
import com.aipaca.android.data.db.entity.BookmarkEntity
import com.aipaca.android.data.db.entity.ChatEntity
import com.aipaca.android.data.db.entity.DownloadEntity
import com.aipaca.android.data.db.entity.DownloadPartEntity
import com.aipaca.android.data.db.entity.MessageEntity
import com.aipaca.android.data.db.entity.ModelEntity
import com.aipaca.android.data.db.entity.ProfileCacheEntity

@Database(
    entities = [
        ModelEntity::class,
        DownloadEntity::class,
        DownloadPartEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        BenchResultEntity::class,
        ProfileCacheEntity::class,
        BookmarkEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AiPacaDatabase : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    abstract fun downloadDao(): DownloadDao

    abstract fun downloadPartDao(): DownloadPartDao

    abstract fun chatDao(): ChatDao

    abstract fun messageDao(): MessageDao

    abstract fun benchResultDao(): BenchResultDao

    abstract fun profileCacheDao(): ProfileCacheDao

    abstract fun bookmarkDao(): BookmarkDao

    companion object {

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmarks " +
                        "(repoId TEXT NOT NULL PRIMARY KEY, addedAt INTEGER NOT NULL)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_parts ADD COLUMN downloadedBytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN mode TEXT NOT NULL DEFAULT 'chat'")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCalls TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN toolsSupported INTEGER")
            }
        }
    }
}
