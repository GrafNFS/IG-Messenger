package ru.insolent.castoms.ig

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.lifecycleScope // Для lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.insolent.castoms.ig.crypto.CryptoManager
import ru.insolent.castoms.ig.databinding.ActivityLoginBinding
import ru.insolent.castoms.ig.network.*
import ru.insolent.castoms.ig.service.MessageReceiverService
import ru.insolent.castoms.ig.utils.SessionManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var cryptoManager: CryptoManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cryptoManager = CryptoManager(this)

        binding.btnLogin.setOnClickListener {
            val username = binding.etLoginUsername.text.toString().trim()
            val password = binding.etLoginPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.toast_emty_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performLogin(username, password)
        }

        binding.tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    private fun performLogin(username: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = getText(R.string.str_login_send)

        lifecycleScope.launch {
            try {
                val request = LoginRequest(username, password)
                val response = NetworkClient.apiService.login(request)

                if (response.isSuccessful && response.body() != null) {
                    val authResponse = response.body()!!

                    NetworkClient.setToken(authResponse.token)

                    // 1. Сохраняем токен и ID
                    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    prefs.edit().putString("user_id", authResponse.user.id).apply()

                    // 2. ПРОВЕРЯЕМ: есть ли уже ключи на этом устройстве?
                    val keysExist = areKeysLocallyStored(authResponse.user.id)

                    if (!keysExist) {
                        // Ключей нет (первый вход на устройстве / данные очищены)
                        val keysUploaded = cryptoManager.generateAndUploadKeys(authResponse.user.id)
                        if (!keysUploaded) {
                            showError("Failed to setup encryption keys")
                            return@launch
                        }
                    } else {
                        // Ключи есть -> можно опционально дослать недостающие One-Time PreKeys на сервер
                        // cryptoManager.syncMissingPreKeys(authResponse.user.id)
                    }

                    // 1. Сохраняем через менеджер
                    val session = SessionManager(this@LoginActivity)
                    session.saveSession(authResponse.token, authResponse.user.id)

                    // 2. Обновляем NetworkClient (на случай если сменился токен)
                    // После получения токена от сервера
                    NetworkClient.setToken(response.body()?.token ?: "")
                    NetworkClient.init(this@LoginActivity)

                    // 3. Подключаем сокет
                    SocketManager.connect(authResponse.user.id)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ требует явного разрешения на уведомления
                        if (ContextCompat.checkSelfPermission(this@LoginActivity, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {

                            Log.d("Login", "Запрос разрешения на уведомления...")
                            ActivityCompat.requestPermissions(
                                this@LoginActivity,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                100 // Код запроса
                            )
                            // НЕ запускаем сервис здесь! Он запустится в onActivityResult или после получения прав.
                        } else {
                            startMessageService()
                        }
                    } else {
                        // Для Android 12 и ниже разрешение не требуется
                        startMessageService()
                    }
                } else {
                    showError("Invalid credentials")
                }
            } catch (e: Exception) {
                showError("Network error: ${e.message}")
            } finally {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = getText(R.string.str_login)
            }
        }
    }

    private fun startMessageService() {
        // 4. Переход на главный экран
        val intent = Intent(this@LoginActivity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // Обработка результата запроса прав
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMessageService() // Пользователь дал разрешение, запускаем сервис
            } else {
                Log.w("Splash", "Разрешение на уведомления отклонено. Сообщения в фоне работать не будут.")
                // Можно показать пользователю Toast или диалог с объяснением, зачем это нужно
            }
        }
    }

    private fun areKeysLocallyStored(userId: String): Boolean {
        val prefs = getSharedPreferences("crypto_keys", Context.MODE_PRIVATE)
        return prefs.contains("${userId}_identity_private")
    }

    private fun showError(message: String) {
        val shortMessage = if (message.length > 100) message.substring(0, 100) + "..." else message
        Toast.makeText(this@LoginActivity, shortMessage, Toast.LENGTH_LONG).show()
    }
}