package io.github.altoxheater

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Plans STATUS once a command has left; rolls back the command or status request if the SMS was not sent */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (resultCode == Activity.RESULT_OK) {
            if (intent.getStringExtra(KIND) == Heater.KIND_COMMAND) Heater.commandSent(ctx)
            return
        }
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
