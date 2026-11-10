package ru.insolent.castoms.ig.database

import androidx.room.*

@Dao
interface ContactDao {
    // Вставка или обновление контакта
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    // Получение контакта по ID
    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContactById(contactId: String): ContactEntity?

    // Получение всех контактов (для экрана контактов)
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAllContacts(): List<ContactEntity>
}