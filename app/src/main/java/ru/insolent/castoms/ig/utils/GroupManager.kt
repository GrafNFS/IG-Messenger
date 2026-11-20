package ru.insolent.castoms.ig.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.insolent.castoms.ig.network.CreateGroupRequest
import ru.insolent.castoms.ig.network.GroupMemberDto
import ru.insolent.castoms.ig.network.GroupResponse
import ru.insolent.castoms.ig.network.NetworkClient

/**
 * Менеджер групп: серверные данные + локальный кэш участников.
 *
 * Группа на клиенте живёт как «псевдо-контакт» (ContactEntity с isGroup=1) —
 * поэтому она появляется в списке чатов/непрочитанных и догоне без
 * переделки ConversationDao.
 */
class GroupManager(private val context: Context) {

    private val messageManager = MessageManager(context)
    private val membersCache = mutableMapOf<String, List<GroupMemberDto>>()

    /** Мои группы: сохраняет псевдо-контакты групп и участников, кэширует состав */
    suspend fun refreshMyGroups(): List<GroupResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = NetworkClient.apiService.getMyGroups()
            if (!resp.isSuccessful || resp.body() == null) return@withContext emptyList()

            val groups = resp.body()!!
            membersCache.clear()
            for (g in groups) {
                // Псевдо-контакт группы
                messageManager.saveContact(
                    id = g.id, username = g.id, displayName = g.name,
                    isOnline = false, isGroup = true
                )
                // Участники — как обычные контакты (нужны для сессий/ключей и имён)
                for (m in g.members) {
                    messageManager.saveContact(
                        id = m.userId, username = m.username ?: m.userId,
                        displayName = m.displayName, isOnline = false, isGroup = false
                    )
                }
                membersCache[g.id] = g.members
            }
            groups
        } catch (e: Exception) {
            Log.e(TAG, "refreshMyGroups failed", e)
            emptyList()
        }
    }

    /** Состав группы (кэш → сервер). Участники сохраняются как контакты. */
    suspend fun getMembers(groupId: String): List<GroupMemberDto> = withContext(Dispatchers.IO) {
        membersCache[groupId]?.let { return@withContext it }
        try {
            val resp = NetworkClient.apiService.getGroupMembers(groupId)
            if (!resp.isSuccessful || resp.body() == null) return@withContext emptyList()
            val members = resp.body()!!
            for (m in members) {
                messageManager.saveContact(
                    id = m.userId, username = m.username ?: m.userId,
                    displayName = m.displayName, isOnline = false, isGroup = false
                )
            }
            membersCache[groupId] = members
            members
        } catch (e: Exception) {
            Log.e(TAG, "getMembers failed", e)
            emptyList()
        }
    }

    /** Создать группу на сервере и подтянуть её локально */
    suspend fun createGroup(name: String, memberIds: List<String>): GroupResponse? =
        withContext(Dispatchers.IO) {
            try {
                val resp = NetworkClient.apiService.createGroup(CreateGroupRequest(name, memberIds))
                if (resp.isSuccessful && resp.body() != null) {
                    refreshMyGroups()
                    resp.body()
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "createGroup failed", e)
                null
            }
        }

    /** Добавить участников (только OWNER; сервер проверит) */
    suspend fun addMembers(groupId: String, userIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var allOk = true
                for (uid in userIds) {
                    val resp = NetworkClient.apiService.addGroupMember(groupId, mapOf("userId" to uid))
                    if (!resp.isSuccessful) allOk = false
                }
                refreshMyGroups() // обновить кэш + контакты новых участников
                allOk
            } catch (e: Exception) {
                Log.e(TAG, "addMembers failed", e)
                false
            }
        }

    /** Выйти из группы (не-OWNER). Локально удаляем псевдо-контакт — чат исчезнет из списка */
    suspend fun leaveGroup(groupId: String, myId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val resp = NetworkClient.apiService.removeGroupMember(groupId, myId)
                if (resp.isSuccessful) {
                    messageManager.deleteContact(groupId)
                    membersCache.remove(groupId)
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "leaveGroup failed", e)
                false
            }
        }

    companion object {
        private const val TAG = "GroupManager"
    }
}
