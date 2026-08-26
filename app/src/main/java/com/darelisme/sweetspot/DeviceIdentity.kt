package com.darelisme.sweetspot

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

/**
 * Persistent device identity for relay pairing.
 *
 * A random `tv_`-prefixed hex id is generated on first run and reused forever.
 * Hardware identifiers (MAC, serial, ANDROID_ID) are deliberately not used.
 */
object DeviceIdentity {

    private const val PREFS = "sweetspot_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAN_API_TOKEN = "lan_api_token"
    private const val KEY_NAME = "device_name"

    @Volatile
    private var cached: String? = null


    @Volatile
    private var cachedLanApiToken: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = generate()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            cached = id
            return id
        }
    }


    fun getLanApiToken(context: Context): String = synchronized(this) {
        cachedLanApiToken ?: getOrCreateSecret(context, KEY_LAN_API_TOKEN).also { cachedLanApiToken = it }
    }

    /**
     * Human-facing TV name shown in the dashboard. Defaults to a generic name
     * with the short id suffix; overridable later from the UI.
     */
    fun getName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.let { return it }
        val suffix = get(context).takeLast(4)
        return "SweetSpot TV ($suffix)"
    }

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, name).apply()
    }

    private fun generate(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return "tv_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateSecret(context: Context, key: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.takeIf { it.length >= 40 }?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = "ss_" + Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        prefs.edit().putString(key, value).apply()
        return value
    }
}
