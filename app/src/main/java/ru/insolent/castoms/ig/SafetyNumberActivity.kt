package ru.insolent.castoms.ig

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.crypto.SafetyNumberHelper
import ru.insolent.castoms.ig.crypto.SecureKeyStore
import ru.insolent.castoms.ig.databinding.ActivitySafetyNumberBinding
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.SessionManager

class SafetyNumberActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySafetyNumberBinding
    private var contactId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySafetyNumberBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        contactId = intent.getStringExtra("EXTRA_CONTACT_ID") ?: run { finish(); return }
        binding.tvPeerName.text = intent.getStringExtra("EXTRA_NAME") ?: "Собеседник"

        val prefs = getSharedPreferences("safety_prefs", MODE_PRIVATE)
        val key = "verified_$contactId"
        updateVerifiedUi(prefs.getBoolean(key, false))

        binding.btnMarkVerified.setOnClickListener {
            val now = !prefs.getBoolean(key, false)
            prefs.edit().putBoolean(key, now).apply()
            updateVerifiedUi(now)
        }
        load()
    }

    private fun updateVerifiedUi(verified: Boolean) {
        binding.tvVerifiedState.visibility = if (verified) View.VISIBLE else View.GONE
        binding.btnMarkVerified.text = if (verified) "Снять отметку проверки" else "Отметить как проверенный"
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val myId = SessionManager(this@SafetyNumberActivity).getUserId() ?: return@launch
                val deviceId = SessionManager(this@SafetyNumberActivity).getCryptoDeviceId()
                val secureStore = SecureKeyStore(this@SafetyNumberActivity)
                val myIdentityB64 = secureStore.getString("${myId}_${deviceId}_identity_public", null)
                    ?: secureStore.getString("${myId}_identity_public", null)   // легаси

                if (myIdentityB64 == null) { binding.tvSafetyNumber.text = "Ключи устройства не найдены"; return@launch }

                val resp = NetworkClient.apiService.getIdentityKey(contactId)
                val theirIdentity = if (resp.isSuccessful) resp.body()?.identityKey else null
                if (theirIdentity.isNullOrEmpty() || theirIdentity == "pending") {
                    binding.tvSafetyNumber.text = "Собеседник ещё не загрузил ключи"; return@launch
                }

                val number = SafetyNumberHelper.compute(myIdentityB64, theirIdentity)
                binding.tvSafetyNumber.text = SafetyNumberHelper.format(number)
                binding.tvHint.visibility = View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@SafetyNumberActivity, "Ошибка загрузки ключей", Toast.LENGTH_SHORT).show()
            }
        }
    }
}