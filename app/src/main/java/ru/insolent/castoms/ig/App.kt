package ru.insolent.castoms.ig

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.service.MessageReceiverService
import ru.insolent.castoms.ig.utils.SessionManager

class App : Application(), Application.ActivityLifecycleCallbacks {
    private var isServiceBound = false
    private var activeAllowedScreens = 0 // Счетчик открытых рабочих экранов

    // Список экранов, на которых сервис запускать НЕ нужно
    private val ignoredActivities = setOf(
        SplashActivity::class.java,
        LoginActivity::class.java,
        RegisterActivity::class.java,
        OtpVerificationActivity::class.java
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isServiceBound = true
            Log.d("App", "MessageReceiverService подключен для рабочих экранов")
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            isServiceBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Инициализируем NetworkClient один раз при старте
        NetworkClient.init(this)
        registerActivityLifecycleCallbacks(this)

        // Сброс счётчика при холодном старте приложения
        activeAllowedScreens = 0
        isServiceBound = false
    }

    override fun onActivityStarted(activity: Activity) {
        // Если текущий экран в черном списке — ничего не делаем
        if (activity::class.java in ignoredActivities) return

        // Проверяем, что пользователь точно авторизован
        val userId = SessionManager(this).getUserId()
        if (userId.isNullOrEmpty()) return

        activeAllowedScreens++

        // Привязываем сервис только при открытии первого рабочего экрана
        if (activeAllowedScreens == 1 && !isServiceBound) {
            val intent = Intent(this, MessageReceiverService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity::class.java in ignoredActivities) return

        if (activeAllowedScreens > 0) {
            activeAllowedScreens--
        }

        // Отвязываем сервис только когда закрыты/свернуты ВСЕ рабочие экраны
        if (activeAllowedScreens == 0 && isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            Log.d("App", "Все рабочие экраны закрыты, сервис отключен")
        }
    }

    // Остальные обязательные методы интерфейса оставляем пустыми
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}