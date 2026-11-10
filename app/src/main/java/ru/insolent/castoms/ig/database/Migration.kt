package ru.insolent.castoms.ig.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Миграция с версии 1 на 2: добавляем таблицу messages
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `messages` (
                `chatId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `senderId` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `encryptedPayload` TEXT,
                `timestamp` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `isIncoming` INTEGER NOT NULL,
                PRIMARY KEY(`chatId`, `messageId`)
            )
        """.trimIndent())

        // Создаём индекс для быстрого поиска по чату
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS `idx_messages_chatId` ON `messages` (`chatId`)
        """.trimIndent())
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `contacts` (
                `id` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `displayName` TEXT,
                `avatarUrl` TEXT,
                `lastSeen` INTEGER,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
    }
}