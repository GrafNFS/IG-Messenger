package ru.insolent.castoms.ig.fragments

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.ChatActivity
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.adapters.ChatAdapter
import ru.insolent.castoms.ig.databinding.FragmentChatsBinding
import ru.insolent.castoms.ig.network.SocketManager
import ru.insolent.castoms.ig.utils.MessageManager

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messageManager: MessageManager
    private var isFabVisible = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        messageManager = MessageManager(requireContext())

        setupRecyclerView()
        setupFAB()
        loadConversations()
        observeUserStatus()
    }

    private fun setupRecyclerView() {
        binding.rvChats.layoutManager = LinearLayoutManager(requireContext())
        chatAdapter = ChatAdapter() { conversation ->
            openChat(conversation.contactId, conversation.contactName)
        }
        binding.rvChats.adapter = chatAdapter
    }

    private fun setupFAB() {
        binding.fabNewChat.setOnClickListener {
            // TODO: Открыть диалог создания нового чата
            Log.d("ChatsFragment", "FAB clicked")
        }

        binding.rvChats.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy > 10 && isFabVisible) hideFAB()
                else if (dy < -5 && !isFabVisible) showFAB()
            }
        })
    }

    private fun loadConversations() {
        viewLifecycleOwner.lifecycleScope.launch {
            messageManager.getConversations().collect { conversations ->
                Log.d("ChatsFragment", "🔄 Flow emitted ${conversations.size} conversations")
                chatAdapter.submitList(conversations)
            }
        }
    }

    private fun observeUserStatus() {
        // Глобальный слушатель статусов — работает пока Fragment жив
        SocketManager.onUserStatusChanged = { userId, isOnline ->
            Log.d("ChatsFragment", "📡 User $userId isOnline = $isOnline")
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    messageManager.updateContactOnlineStatus(userId, isOnline)
                } catch (e: Exception) {
                    Log.e("ChatsFragment", "❌ Failed to update DB status", e)
                }
            }
        }
    }

    private fun openChat(contactId: String, contactName: String) {
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("EXTRA_CONTACT_ID", contactId)
            putExtra("EXTRA_CONTACT_NAME", contactName)
            putExtra("EXTRA_CONTACT_AVATAR", R.drawable.ic_default_avatar)
        }
        startActivity(intent)
    }

    private fun hideFAB() {
        isFabVisible = false
        ObjectAnimator.ofFloat(binding.fabNewChat, "translationY", binding.fabNewChat.height + 40f).apply {
            duration = 300; interpolator = AccelerateDecelerateInterpolator(); start()
        }
        binding.fabNewChat.hide()
    }

    private fun showFAB() {
        isFabVisible = true
        ObjectAnimator.ofFloat(binding.fabNewChat, "translationY", 0f).apply {
            duration = 300; interpolator = AccelerateDecelerateInterpolator(); start()
        }
        binding.fabNewChat.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}