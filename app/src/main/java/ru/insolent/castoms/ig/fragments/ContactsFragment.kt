package ru.insolent.castoms.ig.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.ChatActivity
import ru.insolent.castoms.ig.FriendRequestsActivity
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.adapters.ContactsAdapter
import ru.insolent.castoms.ig.databinding.FragmentContactsBinding
import ru.insolent.castoms.ig.models.Contact
import ru.insolent.castoms.ig.network.AddContactRequest
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.SessionManager

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactsAdapter: ContactsAdapter
    private lateinit var sessionManager: SessionManager
    private val myContactsCache = mutableListOf<Contact>()
    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        setupRecyclerView()
        setupSearch()
        setupFriendRequestsButton()
        fetchMyContacts()
        countPendingRequests()
    }

    private fun setupRecyclerView() {
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        contactsAdapter = ContactsAdapter(
            contacts = emptyList(),
            onItemClick = { contact -> openChatWith(contact) },
            onAddClick = { contact -> sendFriendRequestToServer(contact) }
        )
        binding.rvContacts.adapter = contactsAdapter
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                val query = newText?.trim() ?: ""

                if (query.isEmpty()) {
                    contactsAdapter.updateList(myContactsCache)
                    binding.progressBar.visibility = View.GONE
                    return true
                }

                if (query.length < 2) return true

                binding.progressBar.visibility = View.VISIBLE
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    performServerSearch(query)
                }
                return true
            }
        })
    }

    private fun setupFriendRequestsButton() {
        binding.linFrendsRequest.visibility = View.GONE
        binding.linFrendsRequest.setOnClickListener {
            startActivity(Intent(requireContext(), FriendRequestsActivity::class.java))
        }
    }

    private fun fetchMyContacts() {
        val userId = sessionManager.getUserId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getMyContacts(userId)
                if (response.isSuccessful && response.body() != null) {
                    val apiContacts = response.body()!!
                    myContactsCache.clear()
                    val mappedContacts = apiContacts.map { apiContact ->
                        Contact(
                            id = apiContact.id,
                            name = apiContact.displayName ?: apiContact.username,
                            status = "Tap to chat",
                            avatarResId = R.drawable.ic_default_avatar,
                            avatarUrl = apiContact.avatarUrl,
                            isMyContact = true
                        )
                    }
                    myContactsCache.addAll(mappedContacts)
                    contactsAdapter.updateList(myContactsCache)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка сети", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun countPendingRequests() {
        val userId = sessionManager.getUserId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getPendingCountRequests(userId)
                if (response.isSuccessful) {
                    if (response.body() != null && response.body() != 0) {
                        binding.linFrendsRequest.visibility = View.VISIBLE
                        binding.tvCountPending.text = "${response.body()}"
                    } else {
                        binding.linFrendsRequest.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("ContactsFragment", "Ошибка ${e}")
            }
        }
    }

    private suspend fun performServerSearch(query: String) {
        try {
            val currentUserId = sessionManager.getUserId() ?: return
            val response = NetworkClient.apiService.searchUsers(query, currentUserId)
            if (response.isSuccessful) {
                val searchedUsers = response.body() ?: emptyList()
                val mergedResults = searchedUsers.map { user ->
                    val isAlreadyContact = myContactsCache.any { it.id == user.id }
                    Contact(
                        id = user.id,
                        name = user.displayName ?: user.username,
                        status = if (isAlreadyContact) "Tap to chat" else "Не в контактах",
                        avatarResId = R.drawable.ic_default_avatar,
                        avatarUrl = user.avatarUrl,
                        isMyContact = isAlreadyContact
                    )
                }
                contactsAdapter.updateList(mergedResults)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun sendFriendRequestToServer(contact: Contact) {
        val currentUserId = sessionManager.getUserId() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = AddContactRequest(currentUserId, contact.id)
                val response = NetworkClient.apiService.sendFriendRequest(request)
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "✅ Заявка отправлена", Toast.LENGTH_SHORT).show()
                    contactsAdapter.markAsRequested(contact.id)
                    val index = myContactsCache.indexOfFirst { it.id == contact.id }
                    if (index != -1) {
                        myContactsCache[index] = contact.copy(isRequested = true)
                    }
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Ошибка сервера"
                    Toast.makeText(requireContext(), "❌ $errorMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка сети: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChatWith(contact: Contact) {
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("EXTRA_CONTACT_ID", contact.id)
            putExtra("EXTRA_CONTACT_NAME", contact.name)
            putExtra("EXTRA_CONTACT_AVATAR", R.drawable.ic_default_avatar)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        fetchMyContacts()
        countPendingRequests()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}