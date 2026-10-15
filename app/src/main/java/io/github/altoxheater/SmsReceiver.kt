package io.github.altoxheater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Passes SMS from the Altox number to Heater.onReply, gluing multipart messages together */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        parts.groupBy { it.originatingAddress }.forEach { (sender, msgs) ->
            if (Heater.isFromAltox(ctx, sender)) Heater.onReply(ctx, msgs.joinToString("") { it.messageBody.orEmpty() })
        }
    }
}
