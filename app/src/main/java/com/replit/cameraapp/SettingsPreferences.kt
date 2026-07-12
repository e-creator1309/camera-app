package com.replit.cameraapp

import android.content.Context

private const val PREFS_NAME = "camera_app_settings"
private const val KEY_SCAN_DOCUMENTS_ENABLED = "scan_documents_enabled"

/** Thin, persisted wrapper around the app's settings so they survive app restarts. */
object SettingsPreferences {

    fun isScanDocumentsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCAN_DOCUMENTS_ENABLED, false)

    fun setScanDocumentsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCAN_DOCUMENTS_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
