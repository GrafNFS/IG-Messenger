package ru.insolent.castoms.ig

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.insolent.castoms.ig.databinding.ActivityOtpVerificationBinding

class OtpVerificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOtpVerificationBinding
    private var email: String = ""
    private var isLogin: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtpVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        email = intent.getStringExtra("EXTRA_EMAIL") ?: "your email"
        isLogin = intent.getBooleanExtra("EXTRA_IS_LOGIN", true)
        binding.tvOtpHint.text = "We sent a 6-digit code to $email"

        binding.btnVerifyOtp.setOnClickListener {
            val code = binding.etOtpCode.text.toString().trim()
            if (code.length < 4) {
                Toast.makeText(this, "Please enter a valid code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Успешная верификация
            // Сохраняем состояние авторизации
            val prefs = getSharedPreferences("auth_prefs", MODE_PRIVATE)
            prefs.edit()
                .putBoolean("is_logged_in", true)
                .putString("username", intent.getStringExtra("EXTRA_USERNAME") ?: "User")
                .apply()

            // Переход на главный экран с очисткой стека
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(mainIntent)
            finish()
        }

        binding.tvResendOtp.setOnClickListener {
            Toast.makeText(this, "Code resent to $email", Toast.LENGTH_SHORT).show()
        }
    }
}