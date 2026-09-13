package com.veenstra.discgolfscore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The *only* way to configure the cloud-saves endpoint (`CLOUD_SAVES.md` section 2 "Config over
 * adb"): an explicit `adb shell am broadcast` naming this receiver's component directly, carrying
 * the Apps Script `/exec` URL and the shared secret as string extras. There is no on-watch way to
 * type either one in — `CLOUD_SAVES.md`'s own reasoning: a `/exec` URL is "~70 characters of
 * base64-ish noise," entering it on a watch keyboard or by voice dictation is not a real option,
 * and offering a `TextInputLauncher` for it would make it *look* like one.
 *
 * **The exact command, needed again on a new watch a year from now** (`CLOUD_SAVES.md` section 5
 * item 3 asks for this to be written down right here, not just in a setup doc that's easy to lose
 * track of):
 *
 * ```
 * adb shell am broadcast -n com.veenstra.discgolfscore/.SyncConfigReceiver \
 *   --es url "https://script.google.com/macros/s/AKfycb.../exec" \
 *   --es secret "your-shared-secret"
 * ```
 *
 * **The `-n com.veenstra.discgolfscore/.SyncConfigReceiver` is not optional.** An *implicit*
 * broadcast (one with no explicit component/package target) to a manifest-declared receiver has
 * been blocked since Android 8 (API 26) — `CLOUD_SAVES.md` section 5 item 3. Without `-n`, `adb`
 * reports success and this receiver simply never runs; there is no error to notice.
 *
 * Both extras are required and both must be non-blank — a broadcast missing either (a typo in the
 * flag name, an empty value) is silently ignored rather than partially overwriting a working
 * config with a blank URL or a blank secret. There is deliberately no "clear via adb" — clearing
 * is a deliberate, on-watch action (`CLOUD_SAVES.md` section 6 Phase D's `CLEAR CONFIG` row), not
 * something a mistyped adb command should be able to do by accident.
 *
 * [onReceive] must return quickly (the system can kill the process shortly after it returns), but
 * writing to [DataStoreSyncConfigStore] is a suspend function — [goAsync] extends the receiver's
 * lifetime just long enough for that write to land on [Dispatchers.IO] before
 * [android.content.BroadcastReceiver.PendingResult.finish] lets the system reclaim it, the
 * standard pattern for "a `BroadcastReceiver` that needs to do real I/O."
 */
class SyncConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val secret = intent.getStringExtra(EXTRA_SECRET)?.trim().orEmpty()
        if (url.isEmpty() || secret.isEmpty()) return

        val store = DataStoreSyncConfigStore(context.applicationContext)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                store.saveConfig(url, secret)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** The `--es url "…"` extra — the Apps Script deployment's `/exec` URL. */
        const val EXTRA_URL = "url"

        /** The `--es secret "…"` extra — matched against the Apps Script's own `SECRET` script property (`CLOUD_SAVES.md` section 2 "Auth"). */
        const val EXTRA_SECRET = "secret"
    }
}
