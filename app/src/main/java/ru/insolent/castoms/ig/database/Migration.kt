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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `signal_records` (
                `key` TEXT NOT NULL,
                `encryptedValue` TEXT NOT NULL,
                PRIMARY KEY(`key`)
            )
        """.trimIndent())
    }
}

// Миграция 4 -> 5: флаг isGroup у контактов (группы = псевдо-контакты)
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `contacts` ADD COLUMN `isGroup` INTEGER NOT NULL DEFAULT 0"
        )
    }
}
