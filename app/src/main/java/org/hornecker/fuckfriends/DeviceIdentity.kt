package org.hornecker.fuckfriends

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Verwaltet die lokale Geräte-Identität.
 * Jedes Gerät bekommt eine zufällige, stabile deviceId (UUID) und einen
 * vom Nutzer vergebenen Anzeigenamen (z.B. "Eike", "Max").
 */
object DeviceIdentity {

    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DISPLAY_NAME = "display_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stabile, zufällige ID für dieses Gerät. Wird beim ersten Zugriff erzeugt. */
    fun getDeviceId(context: Context): String {
        val p = prefs(context)
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    /** Der vom Nutzer gewählte Anzeigename, z.B. "Eike". Null, falls noch nicht gesetzt. */
    fun getDisplayName(context: Context): String? =
        prefs(context).getString(KEY_DISPLAY_NAME, null)

    fun setDisplayName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    fun hasDisplayName(context: Context): Boolean =
        !getDisplayName(context).isNullOrBlank()
}