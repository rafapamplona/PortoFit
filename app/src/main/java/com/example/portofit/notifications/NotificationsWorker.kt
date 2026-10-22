package com.example.portofit.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.portofit.PortoFitApplication
import java.util.concurrent.TimeUnit

/**
 * Consulta periódica com a app fechada (docs/notifications-api.md §15.3). Corre no processo da app, com o mesmo
 * AppContainer (e o mesmo SessionManager) dos ecrãs; a lógica está no NotificationPoller. O Android decide quando corre:
 * não há execução exata, nem execução forçada, nem serviço em primeiro plano.
 */
class NotificationsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.success()
        val container = (applicationContext as PortoFitApplication).container
        return when (container.notificationPoller.poll(account)) {
            PollOutcome.RETRY -> Result.retry()
            PollOutcome.DONE, PollOutcome.SKIPPED -> Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val TAG = "notifications-poll"
    }
}

/** Um trabalho periódico por conta (só a conta da sessão tem trabalho agendado), de 15 em 15 min, com rede. */
class WorkManagerScheduler(context: Context) : WorkScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(account: String) {
        val request = PeriodicWorkRequestBuilder<NotificationsWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(NotificationsWorker.KEY_ACCOUNT to account))
            .addTag(NotificationsWorker.TAG)
            .build()
        workManager.enqueueUniquePeriodicWork("${NotificationsWorker.TAG}-$account", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() {
        workManager.cancelAllWorkByTag(NotificationsWorker.TAG)
    }

    private companion object {
        /** O mínimo do Android. */
        const val PERIOD_MINUTES = 15L
    }
}
