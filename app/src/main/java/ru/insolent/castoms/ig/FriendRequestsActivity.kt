package ru.insolent.castoms.ig

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.adapters.FriendRequestAdapter
import ru.insolent.castoms.ig.databinding.ActivityFriendRequestsBinding
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.RespondRequestDto
import ru.insolent.castoms.ig.utils.SessionManager

class FriendRequestsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFriendRequestsBinding
    private lateinit var adapter: FriendRequestAdapter
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        sessionManager = SessionManager(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FriendRequestAdapter(emptyList(),
            onAccept = { requestId -> respondToRequest(requestId, "ACCEPTED") },
            onReject = { requestId -> respondToRequest(requestId, "REJECTED") }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = adapter

        loadPendingRequests()
    }

    private fun loadPendingRequests() {
        val userId = sessionManager.getUserId() ?: return
        lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getPendingRequests(userId)
                if (response.isSuccessful) {
                    adapter.updateList(response.body() ?: emptyList())
                }
            } catch (e: Exception) {
                Toast.makeText(this@FriendRequestsActivity, "Ошибка сети", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun respondToRequest(requestId: String, status: String) {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.respondToFriendRequest(
                    RespondRequestDto(requestId, status)
                )
                if (response.isSuccessful) {
                    Toast.makeText(this@FriendRequestsActivity,
                        if (status == "ACCEPTED") "Теперь вы друзья!" else "Заявка отклонена",
                        Toast.LENGTH_SHORT).show()
                    loadPendingRequests() // Перезагружаем список
                }
            } catch (e: Exception) {
                Toast.makeText(this@FriendRequestsActivity, "Ошибка", Toast.LENGTH_SHORT).show()
            }
        }
    }
}