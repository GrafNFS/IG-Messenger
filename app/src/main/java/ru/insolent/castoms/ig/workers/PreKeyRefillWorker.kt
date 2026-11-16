package ru.insolent.castoms.ig.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.insolent.castoms.ig.crypto.CryptoManager
import ru.insolent.castoms.ig.utils.SessionManager
import java.util.concurrent.TimeUnit

/**
 * Страховочная периодическая ротация one-time prekeys (раз в сутки),
 * если пользователь долго не открывает приложение.
 * Основной триггер ротации — в SocketManager при подключении.
 */
class PreKeyRefillWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = SessionManager(applicationContext).getUserId()
            ?: return Result.failure()

        return try {
            val ok = CryptoManager(applicationContext).refillOneTimePreKeysIfNeeded(userId)
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "prekey_refill"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PreKeyRefillWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP, // не дублируем, если уже запланировано
                    request
                )
        }
    }
}
