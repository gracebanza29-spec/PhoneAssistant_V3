package com.phoneassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.phoneassistant.service.CallerIdService

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED ->
                if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING)
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) else null
            "android.intent.action.NEW_OUTGOING_CALL" ->
                intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            else -> null
        } ?: return

        context.startForegroundService(
            Intent(context, CallerIdService::class.java).putExtra("number", number)
        )
    }
}
