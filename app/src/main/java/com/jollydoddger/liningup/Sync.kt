package com.jollydoddger.liningup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * ntfy.sh as a tiny mailbox. No account, no server of our own: each phone
 * posts its sealed answer to the couple's topic and reads back whatever is
 * there. The relay keeps a message for 12 hours, so [Sync] resends the day's
 * answer every few hours to keep it from ageing out before 2am.
 */
object Relay {
    private const val SERVER = "https://ntfy.sh"

    fun post(topic: String, body: String) {
        val c = URL("$SERVER/$topic").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) error("relay said $code")
        } finally { c.disconnect() }
    }

    /** Every message the relay still holds for the topic, oldest first. */
    fun read(topic: String): List<String> {
        val c = URL("$SERVER/$topic/json?poll=1&since=all").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            val code = c.responseCode
            if (code !in 200..299) error("relay said $code")
            return c.inputStream.bufferedReader().readLines().mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    if (o.optString("event") == "message") o.optString("message") else null
                }.getOrNull()
            }
        } finally { c.disconnect() }
    }
}

object Sync {
    private val lock = Mutex()

    /**
     * Send mine if it has not gone yet (or is due a resend), then read theirs.
     * Notifies about a new answer of theirs, and about a match, only when the
     * app is not open — on screen it is already in front of you.
     */
    suspend fun run(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            Store.init(context)
            val couple = Store.couple ?: return@withContext
            val now = System.currentTimeMillis()
            val day = Store.today(now)
            try {
                val mine = Store.mine()
                if (Store.dirty || Statuses.shouldRepublish(mine, day, Store.lastPublishedAt, now)) {
                    // A cleared or yesterday's answer still has to be sent once,
                    // or the partner keeps seeing the ✅ that was taken back.
                    val latest = Store.latestMine()
                    if (latest != null) Relay.post(couple.topic, couple.seal(latest.wire()))
                    Store.markPublished(now)
                }
                val all = Relay.read(couple.topic).mapNotNull { couple.open(it) }.mapNotNull { Status.parse(it) }
                val theirs = Statuses.partner(all, Store.me, day)
                Store.heard(theirs, now)
                if (theirs != null && Store.firstSight(theirs) && !Store.foreground && theirs.ok != null) {
                    Notify.partner(context, theirs)
                }
                if (Statuses.isMatch(Store.mine(), theirs, day) && Store.firstMatchToday() && !Store.foreground) {
                    Notify.match(context)
                }
            } catch (t: Throwable) {
                Store.failed(t.message ?: t.javaClass.simpleName)
                throw t
            }
        }
    }

    fun soon(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
    }

    /** Every 15 minutes, Android's floor: how a new answer reaches a closed app. */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching { Sync.run(applicationContext) }
            .fold({ Result.success() }, { if (runAttemptCount < 5) Result.retry() else Result.failure() })
}
