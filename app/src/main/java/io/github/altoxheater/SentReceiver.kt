package io.github.altoxheater

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rolls back the pending command or status request if the SMS was not sent */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (resultCode == Activity.RESULT_OK) return
        val message = "SMS не отправлено (код $resultCode)"
        when (intent.getStringExtra(KIND)) {
            Heater.KIND_COMMAND -> Heater.commandFailed(ctx, message)
            Heater.KIND_STATUS -> Heater.statusFailed(ctx, message)
        }
    }

    companion object {
        const val KIND = "kind"
    }
}
