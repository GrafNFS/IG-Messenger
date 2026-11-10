package ru.insolent.castoms.ig

import android.Manifest
import android.R.id.progress
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.insolent.castoms.ig.model.AppCheckVersion
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.SocketManager
import ru.insolent.castoms.ig.service.MessageReceiverService
import ru.insolent.castoms.ig.utils.AutoUpdaterManager
import ru.insolent.castoms.ig.utils.SessionManager

class SplashActivity : AppCompatActivity() {

    private lateinit var ivBubbleOutline: ImageView
    private lateinit var tvIG: TextView
    private lateinit var viewCursor: View
    private lateinit var linProgressDonwload: LinearLayout
    private lateinit var progDonwloadApk: ProgressBar
    private var update: AppCheckVersion? = null
    private var check = AutoUpdaterManager(this)

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        initViews()
        animateSplash()
    }

    private fun initViews() {
        ivBubbleOutline = findViewById(R.id.ivBubbleOutline)
        tvIG = findViewById(R.id.tvIG)
        viewCursor = findViewById(R.id.viewCursor)
        linProgressDonwload = findViewById(R.id.linProgressDonwload)
        progDonwloadApk = findViewById(R.id.progDonwloadApk)

        linProgressDonwload.visibility = View.GONE
    }

    private fun animateSplash() {
        // Анимация появления пузыря
        val bubbleScale = ObjectAnimator.ofFloat(ivBubbleOutline, "scaleX", 0f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
        }
        val bubbleScaleY = ObjectAnimator.ofFloat(ivBubbleOutline, "scaleY", 0f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Анимация появления текста IG
        val textAlpha = ObjectAnimator.ofFloat(tvIG, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 400
        }

        // Анимация курсора (пульсация)
        val cursorAlpha = ObjectAnimator.ofFloat(viewCursor, "alpha", 0f, 1f).apply {
            duration = 300
            startDelay = 800
        }
        val cursorGlow = ObjectAnimator.ofFloat(viewCursor, "alpha", 1f, 0.5f, 1f).apply {
            duration = 1000
            startDelay = 1100
            repeatCount = 2
        }

        // Запуск анимаций
        bubbleScale.start()
        bubbleScaleY.start()
        textAlpha.start()
        cursorAlpha.start()
        cursorGlow.start()

        // Переход на главный экран через 3 секунды
        Handler(Looper.getMainLooper()).postDelayed({

            lifecycleScope.launch {
                try {
                    update = check.checkForUpdate()
                    if (update != null) {
                        confirmUpdate(update)
                    }
                    else {
                        checkLoginAndPermissions()
                    }
                } catch (e: Exception) {
                    Log.e("SplashActivity", "Failed to load profile from server", e)
                }
            }
        }, 3000)
    }

    private fun confirmUpdate(update: AppCheckVersion?) {
        if (update?.mandatoryUpdate == true) {
            AlertDialog.Builder(this)
                .setTitle("Обновить приложение?")
                .setMessage(update?.changeLog)
                .setPositiveButton("Обновить") { _, _ -> updateApp() }
                .show()
        }
        else {
            AlertDialog.Builder(this)
                .setTitle("Обновить приложение?")
                .setMessage(update?.changeLog)
                .setPositiveButton("Обновить") { _, _ -> updateApp() }
                .setNegativeButton("Отмена", { _, _ -> checkLoginAndPermissions() })
                .show()
        }
    }

    private fun updateApp() {
        linProgressDonwload.visibility = View.VISIBLE
        progDonwloadApk.isIndeterminate = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO){
                check.downloadapk(this@SplashActivity,
                    update!!.fileSize,
                    update!!.latestVersion,
                    onProgressUpdate = {
                        progress ->
                        if (progress > 0) {
                            progDonwloadApk.isIndeterminate = false
                        }
                        progDonwloadApk.progress = progress.coerceIn(0, 100)
                        //Log.d("SplashActivity", "Download progress: $progress%")
                    }
                )
            }
        }

    }

    private fun checkLoginAndPermissions() {
        NetworkClient.init(this)
        val session = SessionManager(this)

        if (!session.isLoggedIn()) {
            navigateToLogin()
            return
        }

        val userId = session.getUserId()
        if (userId.isNullOrEmpty()) {
            Log.e("Splash", "User ID is null despite isLoggedIn being true")
            navigateToLogin()
            return
        }
        val deviceId = session.getDeviceId()

        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val fcmToken = task.result
                    if (fcmToken != null && userId != null) {
                        lifecycleScope.launch {
                            NetworkClient.sendFcmTokenToServer(userId, deviceId, fcmToken)
                        }
                    }
                }
            }

        // Подключаем сокет сразу
        SocketManager.connect(userId)

        // Проверяем необходимость запроса разрешений (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

                Log.d("Splash", "Запрос разрешения на уведомления...")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
                // ⚠️ ВАЖНО: НЕ вызываем здесь navigateToMain(). Ждем ответа пользователя в onRequestPermissionsResult!
                return
            }
        }

        // Если разрешение уже есть или версия Android ниже 13
        //startMessageServiceAndNavigate()
        navigateToMain()
    }

    private fun startMessageServiceAndNavigate() {
        startMessageService()
        navigateToMain()
    }

    private fun startMessageService() {
        val serviceIntent = Intent(this, MessageReceiverService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("Splash", "✅ MessageReceiverService запущен")
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // Обработка результата запроса прав
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("Splash", "✅ Разрешение на уведомления получено")
                //startMessageService()
            } else {
                Log.w("Splash", "⚠️ Разрешение на уведомления отклонено. Фоновые сообщения работать не будут.")
                Toast.makeText(this, "Уведомления отключены. Вы можете не получать сообщения в фоне.", Toast.LENGTH_LONG).show()
            }

            // Теперь, после обработки ответа, переходим на главный экран
            navigateToMain()
        }
    }
}