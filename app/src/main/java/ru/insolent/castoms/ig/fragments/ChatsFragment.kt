package ru.insolent.castoms.ig.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.ChatActivity
import ru.insolent.castoms.ig.adapters.ChatAdapter
import ru.insolent.castoms.ig.database.ContactEntity
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.databinding.FragmentChatsBinding
import ru.insolent.castoms.ig.groups.CreateGroupActivity
import ru.insolent.castoms.ig.utils.GroupManager
import ru.insolent.castoms.ig.utils.MessageManager

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var db: CryptoDatabase
    private val messageManager by lazy { MessageManager(requireContext()) }

    private val TAG = "ChatsFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        db = CryptoDatabase.getDatabase(requireContext())

        binding.rvChats.layoutManager = LinearLayoutManager(context)

        chatAdapter = ChatAdapter { conversation ->
            onChatClicked(conversation.contactId)
        }
        binding.rvChats.adapter = chatAdapter

        // 🌟 Подтягиваем группы с сервера (создают псевдо-контакты → появятся в списке)
        viewLifecycleOwner.lifecycleScope.launch {
            try { GroupManager(requireContext()).refreshMyGroups() } catch (e: Exception) {
                Log.e(TAG, "refresh groups failed", e)
            }
        }

        loadConversations()

        binding.fabNewChat.setOnClickListener {
            // 🌟 FAB пока ведёт на создание группы
            startActivity(Intent(requireContext(), CreateGroupActivity::class.java))
        }
    }

    private fun onChatClicked(chatId: String) {
        // Контакт читаем через MessageManager (suspend) — синхронного метода в DAO нет,
        // а Room запрещает запросы на main-потоке
        viewLifecycleOwner.lifecycleScope.launch {
            val contact = messageManager.getContactById(chatId) as? ContactEntity
            val intent = Intent(context, ChatActivity::class.java)
            intent.putExtra("EXTRA_CONTACT_ID", chatId)
            intent.putExtra("EXTRA_CONTACT_NAME", contact?.displayName ?: contact?.username ?: "Собеседник")
            intent.putExtra("EXTRA_IS_GROUP", contact?.isGroup ?: false)   // 🌟
            startActivity(intent)
        }
    }

    private fun loadConversations() {
        viewLifecycleOwner.lifecycleScope.launch {
            db.conversationDao().getConversations().collect { conversations ->
                val sortedConversations = conversations.sortedByDescending { it.lastMessageTime }
                chatAdapter.submitList(sortedConversations)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
