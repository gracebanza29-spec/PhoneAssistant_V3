package com.phoneassistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoneassistant.data.AppStorage
import com.phoneassistant.data.CallerIdRepo
import com.phoneassistant.data.ContactsRepo
import kotlinx.coroutines.*

class CallerIdService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("cid", "Identification appelant", NotificationManager.IMPORTANCE_HIGH)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        startForeground(1001, NotificationCompat.Builder(this, "cid")
            .setContentTitle("Identification en cours…")
            .setSmallIcon(android.R.drawable.ic_menu_call).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("number") ?: run { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            val storage = AppStorage(applicationContext)

            // Bloqué ?
            if (storage.isBlocked(number)) {
                notify("🚫 Appel bloqué", number, null); stopSelf(); return@launch
            }

            // Dans les contacts ?
            val contact = ContactsRepo(applicationContext).findByNumber(number)
            if (contact != null) { stopSelf(); return@launch }

            // API Caller ID
            val info = CallerIdRepo(applicationContext).identify(number)
            val detail = info?.let {
                listOfNotNull(
                    it.carrier?.let { c -> "📡 $c" },
                    it.lineType,
                    it.location?.let { l -> "📍 $l" },
                    if (it.isSpam) "⚠️ SPAM PROBABLE" else null
                ).joinToString("\n").takeIf { s -> s.isNotBlank() }
            }
            notify("📞 Appel entrant inconnu", number, detail)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notify(title: String, number: String, detail: String?) {
        val n = NotificationCompat.Builder(this, "cid")
            .setContentTitle(title).setContentText(number)
            .apply { detail?.let { setStyle(NotificationCompat.BigTextStyle().bigText("$number\n$it")) } }
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1002, n)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
