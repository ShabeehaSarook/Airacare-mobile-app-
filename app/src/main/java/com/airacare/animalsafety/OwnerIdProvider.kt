package com.airacare.animalsafety

import android.content.Context
import android.provider.Settings

object OwnerIdProvider {
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "android_${androidId ?: "unknown"}"
    }
}
