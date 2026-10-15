package io.github.altoxheater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarm planned by Heater after a command: time to send STATUS */
class PollReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Heater.tick(ctx)
}
