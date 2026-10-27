package com.hcc.dvdplayer.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Build
import android.os.Bundle
import com.hcc.dvdplayer.logic.ManagedSettings
import com.hcc.dvdplayer.logic.RestrictionInput

/**
 * Managed configuration wins. The only local preference is the 3-second lock
 * hold, and only when the MDM did not send `lock_controls`.
 */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val restrictions = appContext.getSystemService(RestrictionsManager::class.java)

    fun current(): ManagedSettings {
        val bundle = try {
            restrictions?.applicationRestrictions
        } catch (_: Exception) {
            null
        } ?: Bundle.EMPTY
        return ManagedSettings.parse(read(bundle), localLock = prefs.getBoolean(KEY_LOCK, false))
    }

    fun setLocalLock(locked: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK, locked).apply()
    }

    fun registerRestrictionsListener(context: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    companion object {
        private const val PREFS = "teacher_prefs"
        private const val KEY_LOCK = "local_lock_controls"

        fun read(bundle: Bundle): RestrictionInput {
            val present = bundle.keySet()
            val bools = HashMap<String, Boolean>()
            val ints = HashMap<String, Int>()
            val strings = HashMap<String, String>()
            for (key in present) {
                @Suppress("DEPRECATION")
                val value = bundle.get(key)
                when (value) {
                    is Boolean -> bools[key] = value
                    is Int -> ints[key] = value
                    is Long -> ints[key] = value.toInt()
                    is String -> strings[key] = value
                }
            }
            return RestrictionInput(present, bools, ints, strings)
        }
    }
}
