package ru.insolent.castoms.ig

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope // Для lifecycleScope
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.crypto.CryptoManager
import ru.insolent.castoms.ig.databinding.ActivityRegisterBinding
import ru.insolent.castoms.ig.network.*
import ru.insolent.castoms.ig.service.MessageReceiverService
import ru.insolent.castoms.ig.utils.SessionManager

class RegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding
    private lateinit var cryptoManager: CryptoManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализируем крипто менеджер
        cryptoManager = CryptoManager(this)

        setupPasswordValidation()
        setupClickListeners()
    }

    private fun setupPasswordValidation() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                checkPasswords()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.etRegPassword.addTextChangedListener(watcher)
        binding.etRegConfirmPassword.addTextChangedListener(watcher)
    }

    private fun checkPasswords() {
        val pass1 = binding.etRegPassword.text.toString()
        val pass2 = binding.etRegConfirmPassword.text.toString()

        // 1. Проверка надёжности
        val strength = getPasswordStrength(pass1)
        binding.tvPasswordStrength.text = strength.label
        binding.tvPasswordStrength.setTextColor(strength.color)

        // 2. Проверка совпадения
        if (pass2.isNotEmpty()) {
            if (pass1 == pass2) {
                binding.tvPasswordMatch.setText(R.string.toast_pass_match)
                binding.tvPasswordMatch.setTextColor(ContextCompat.getColor(this, R.color.online))
            } else {
                binding.tvPasswordMatch.setText(R.string.toast_pass_error)
                binding.tvPasswordMatch.setTextColor(ContextCompat.getColor(this, R.color.primary_red))
            }
        } else {
            binding.tvPasswordMatch.text = ""
        }
    }

    private fun getPasswordStrength(password: String): StrengthInfo {
        if (password.isEmpty()) return StrengthInfo(getString(R.string.toast_pass_strength), getColor(R.color.text_secondary_light))
        if (password.length < 6) return StrengthInfo(getString(R.string.txt_pass_weak), getColor(R.color.primary_red))

        var score = 0
        if (password.length >= 8) score++
        if (password.contains(Regex("[A-Z]"))) score++
        if (password.contains(Regex("[a-z]"))) score++
        if (password.contains(Regex("[0-9]"))) score++
        if (password.contains(Regex("[^A-Za-z0-9]"))) score++

        return when (score) {
            in 0..2 -> StrengthInfo(getString(R.string.txt_pass_weak), getColor(R.color.primary_red))
            3 -> StrengthInfo(getString(R.string.txt_pass_medium), getColor(R.color.strength_medium))
            else -> StrengthInfo(getString(R.string.txt_pass_strong), getColor(R.color.online))
        }
    }

    data class StrengthInfo(val label: String, val color: Int)

    private fun setupClickListeners() {
        binding.btnRegister.setOnClickListener {
            val firstName = binding.etRegFirstName.text.toString().trim()
            val lastName = binding.etRegLastName.text.toString().trim()
            val email = binding.etRegEmail.text.toString().trim()
            val username = binding.etRegUsername.text.toString().trim()
            val pass1 = binding.etRegPassword.text.toString()
            val pass2 = binding.etRegConfirmPassword.text.toString()

            // Валидация всех полей
            if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() ||
                username.isEmpty() || pass1.isEmpty() || pass2.isEmpty()) {
                Toast.makeText(this, R.string.toast_emty_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pass1 != pass2) {
                Toast.makeText(this, R.string.toast_pass_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val strength = getPasswordStrength(pass1).label
            if (strength == getString(R.string.txt_pass_weak)) {
                Toast.makeText(this, R.string.toast_pass_weak, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // === ВОТ ЗДЕСЬ НАЧИНАЕТСЯ МАГИЯ ===
            performRegistration(username, email, pass1, firstName, lastName)
        }

        binding.tvGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // === НОВАЯ ФУНКЦИЯ: РЕГИСТРАЦИЯ + ГЕНЕРАЦИЯ КЛЮЧЕЙ ===
    private fun performRegistration(
        username: String,
        email: String,
        password: String,
        firstName: String,
        lastName: String
    ) {
        // Показываем индикатор загрузки (опционально)
        binding.btnRegister.isEnabled = false
        binding.btnRegister.text = getText(R.string.str_registration_send)

        // Используем lifecycleScope для асинхронного запроса
        lifecycleScope.launch {
            try {
                // 1. Отправляем запрос на регистрацию
                val request = RegisterRequest(username, email, password, firstName, lastName)
                val response = NetworkClient.apiService.register(request)

                if (response.isSuccessful && response.body() != null) {
                    val authResponse = response.body()!!

                    // 2. Сохраняем токен и userId
                    NetworkClient.setToken(authResponse.token)

                    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    prefs.edit().putString("user_id", authResponse.user.id).apply()

                    // 3. Генерируем и загружаем криптографические ключи
                    val keysUploaded = cryptoManager.generateAndUploadKeys(authResponse.user.id)

                    if (keysUploaded) {
                        // 4. Успех! Переходим на главный экран
                        Toast.makeText(
                            this@RegisterActivity,
                            "Registration successful!",
                            Toast.LENGTH_SHORT
                        ).show()

                        val session = SessionManager(this@RegisterActivity)
                        session.saveSession(authResponse.token, authResponse.user.id)

                        NetworkClient.setToken(response.body()?.token ?: "")
                        NetworkClient.init(this@RegisterActivity)

                        // 3. !!! ПОДКЛЮЧАЕМ SOCKET !!!
                        SocketManager.connect(authResponse.user.id)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Android 13+ требует явного разрешения на уведомления
                            if (ContextCompat.checkSelfPermission(this@RegisterActivity, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {

                                Log.d("Login", "Запрос разрешения на уведомления...")
                                ActivityCompat.requestPermissions(
                                    this@RegisterActivity,
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
                        // Ошибка генерации ключей
                        showError("Failed to generate encryption keys")
                    }
                } else {
                    // Ошибка регистрации (пользователь уже существует и т.д.)
                    val errorMsg = response.errorBody()?.string() ?: "Registration failed"
                    showError(errorMsg)
                }
            } catch (e: Exception) {
                // Сетевая ошибка или другая исключительная ситуация
                e.printStackTrace()
                showError("Network error: ${e.message}")
            } finally {
                // Возвращаем кнопку в исходное состояние
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = getText(R.string.str_registration)
            }
        }
    }

    private fun startMessageService() {
        // 4. Переход на главный экран
        val intent = Intent(this@RegisterActivity, MainActivity::class.java)
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

    private fun showError(message: String) {
        val shortMessage = if (message.length > 100) message.substring(0, 100) + "..." else message
        Toast.makeText(this@RegisterActivity, shortMessage, Toast.LENGTH_LONG).show()
    }
}