package com.veenstra.discgolfscore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * The one Apps Script endpoint this watch is configured to talk to, plus when it last synced
 * successfully. [url] and [secret] are always both present or both absent — [SyncConfigStore]
 * never stores one without the other, since a URL with no secret can't authenticate and a secret
 * with no URL has nowhere to go. [lastSyncAt] is `null` until the first successful push or pull
 * after this [url]/[secret] pair was set (`CLOUD_SAVES.md` section 2 "Config over adb" — the
 * status line [RoundViewModel]/the Cloud screen shows).
 */
data class SyncConfig(val url: String, val secret: String, val lastSyncAt: Long?)

/**
 * Where the cloud-saves endpoint config is loaded from and persisted to — deliberately its own
 * small interface and its own DataStore file, entirely separate from [PlayerStore]/[CourseStore]/
 * [RoundStore]/[RoundHistoryStore]: this isn't part of the disc golf domain model at all, it's an
 * app setting, with its own writer ([SyncConfigReceiver], over adb — `CLOUD_SAVES.md` section 2
 * "Config over adb") and its own reader ([RoundViewModel]'s sync methods). Giving it a shared file
 * with the round/roster data would be a coincidence of implementation, not a reason.
 */
interface SyncConfigStore {
    suspend fun loadConfig(): SyncConfig?

    /** Overwrites whatever [url]/[secret] were previously configured. [SyncConfig.lastSyncAt] resets to `null` — a freshly (re)pointed endpoint has no sync history of its own yet, even if the *previous* endpoint did. Called only by [SyncConfigReceiver]. */
    suspend fun saveConfig(url: String, secret: String)

    /** The watch screen's `CLEAR CONFIG` (`CLOUD_SAVES.md` section 6 Phase D). Removes the endpoint entirely — after this, [loadConfig] returns `null` until a new `adb` broadcast configures one again. */
    suspend fun clearConfig()

    /** Stamps [SyncConfig.lastSyncAt] after a push or pull actually succeeds. No-op if nothing is configured (there's nothing to stamp), which can only happen if config was cleared out from under an in-flight sync — a race, not a normal path. */
    suspend fun recordSyncAt(epochMillis: Long)
}

private const val SYNC_CONFIG_DATASTORE_NAME = "discgolf_sync_config"
private val SYNC_URL_KEY = stringPreferencesKey("url")
private val SYNC_SECRET_KEY = stringPreferencesKey("secret")
private val SYNC_LAST_SYNC_AT_KEY = longPreferencesKey("last_sync_at")
private val Context.discGolfSyncConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = SYNC_CONFIG_DATASTORE_NAME)

class DataStoreSyncConfigStore(private val context: Context) : SyncConfigStore {

    override suspend fun loadConfig(): SyncConfig? {
        val prefs = context.discGolfSyncConfigDataStore.data.first()
        val url = prefs[SYNC_URL_KEY]
        val secret = prefs[SYNC_SECRET_KEY]
        if (url.isNullOrBlank() || secret.isNullOrBlank()) return null
        return SyncConfig(url = url, secret = secret, lastSyncAt = prefs[SYNC_LAST_SYNC_AT_KEY])
    }

    override suspend fun saveConfig(url: String, secret: String) {
        context.discGolfSyncConfigDataStore.edit { prefs ->
            prefs[SYNC_URL_KEY] = url
            prefs[SYNC_SECRET_KEY] = secret
            prefs.remove(SYNC_LAST_SYNC_AT_KEY)
        }
    }

    override suspend fun clearConfig() {
        context.discGolfSyncConfigDataStore.edit { prefs -> prefs.clear() }
    }

    override suspend fun recordSyncAt(epochMillis: Long) {
        context.discGolfSyncConfigDataStore.edit { prefs ->
            // Only stamp it if a URL is still configured -- see this method's own doc.
            if (prefs[SYNC_URL_KEY] != null) prefs[SYNC_LAST_SYNC_AT_KEY] = epochMillis
        }
    }
}
