package de.greenganja.growguardian.grow.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Die Application stellt den [GrowMonitor] bereit, damit der Worker dieselben Objekte nutzt wie die App. */
interface GrowMonitorHost {
    val growMonitor: GrowMonitor
}

/** Läuft etwa alle 15 Minuten, auch bei geschlossener App (Android kann das im Energiesparmodus verzögern). */
class GrowMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (FirebaseAuth.getInstance().currentUser == null) return Result.success()
        val host = applicationContext as? GrowMonitorHost ?: return Result.failure()
        return try {
            host.growMonitor.runOnce()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Netz weg oder Tuya nicht erreichbar: der nächste reguläre Lauf versucht es wieder
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "grow-monitor"

        /** Nach dem Login aufrufen. Mehrfaches Aufrufen ist unschädlich. */
        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<GrowMonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Beim Logout aufrufen. */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
