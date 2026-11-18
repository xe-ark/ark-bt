package com.emre.dynamicbt.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_BOOT_COMPLETED
        ) {
            val svcIntent = Intent(context, DynamicBtService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            } catch (e: Exception) {
                Log.w("DynamicBtBoot", "startForegroundService failed on $action, trying startService: ${e.message}")
                try {
                    context.startService(svcIntent)
                } catch (e2: Exception) {
                    Log.w("DynamicBtBoot", "Could not start service on $action: ${e2.message}")
                }
            }
        }
    }
}
