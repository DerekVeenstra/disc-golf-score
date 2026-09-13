package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

private sealed interface CloudSyncMode {
    data object Listing : CloudSyncMode
    data object ConfirmingRestore : CloudSyncMode
}

/**
 * Home's `CLOUD` destination (`CLOUD_SAVES.md` section 6 Phase D): a status line (configured or
 * not, and when last synced), `BACK UP NOW`, `RESTORE`, and `CLEAR CONFIG`. There is deliberately
 * no way to *enter* a URL or secret here — only [ConfirmScreen]'s `RESTORE` confirmation and a
 * plain tap for everything else — because configuring the endpoint at all is `adb`-only
 * (`CLOUD_SAVES.md` section 2 "Config over adb": a `/exec` URL is "~70 characters of base64-ish
 * noise," not something worth a watch keyboard). This screen only ever *reports* whether one is
 * configured and lets you clear it.
 *
 * Only `RESTORE` sits behind [ConfirmScreen] (`CLOUD_SAVES.md` section 6 Phase D says so
 * explicitly) — `BACK UP NOW` is a plain upsert with nothing to lose (`CLOUD_SAVES.md` section 2
 * "Push is upsert, never delete"), and `CLEAR CONFIG` only forgets a URL/secret that a fresh `adb`
 * broadcast can restore in seconds, so neither rises to "point of no return" the way an actual
 * `RESTORE` merge — a real network round trip whose result depends on whatever's currently in the
 * sheet — does.
 *
 * [syncStatus] is shown verbatim as the result line once it leaves [SyncStatus.Idle] — the exact
 * "Result text naming counts and warnings" Phase D asks for; [formatPushSummary]/
 * [formatPullSummary] (called by [RoundViewModel], not here) already did the work of shaping that
 * text, so this screen just renders whatever string it's handed.
 */
@Composable
fun CloudSyncScreen(
    syncConfig: SyncConfig?,
    syncStatus: SyncStatus,
    onBackUpNow: () -> Unit,
    onRestore: () -> Unit,
    onClearConfig: () -> Unit,
    onDone: () -> Unit,
) {
    var mode by remember { mutableStateOf<CloudSyncMode>(CloudSyncMode.Listing) }

    when (mode) {
        is CloudSyncMode.ConfirmingRestore -> {
            ConfirmScreen(
                title = "Restore from sheet?",
                detail = "Sheet wins on conflicts — nothing is deleted",
                confirmLabel = "Yes, restore",
                cancelLabel = "Cancel",
                onConfirm = {
                    onRestore()
                    mode = CloudSyncMode.Listing
                },
                onCancel = { mode = CloudSyncMode.Listing },
            )
        }

        is CloudSyncMode.Listing -> {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding.withRoundEdgeInset().withEdgeButtonReserve(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "CLOUD SAVES",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
                    item {
                        Text(
                            text = if (syncConfig != null) "Configured" else "Not configured",
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Text(
                            text = formatLastSyncLine(syncConfig?.lastSyncAt),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                    val resultText = syncResultLine(syncStatus)
                    if (resultText != null) {
                        item {
                            Text(
                                text = resultText,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                        }
                    }
                    item {
                        PickableRow(label = "BACK UP NOW", selected = false, leading = "⬆️", onClick = onBackUpNow)
                    }
                    item {
                        PickableRow(
                            label = "RESTORE",
                            selected = false,
                            leading = "⬇️",
                            onClick = { mode = CloudSyncMode.ConfirmingRestore },
                        )
                    }
                    item {
                        PickableRow(label = "CLEAR CONFIG", selected = false, leading = "🗑️", onClick = onClearConfig)
                    }
                }
                EdgeButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Text(text = "DONE")
                }
            }
        }
    }
}

/** `"Never synced"`, or `"Last synced Sep 13, 2026"` — reuses [formatRoundDate] (the same medium-date formatting a past round's own detail line uses) rather than introducing a second date format into this app. */
private fun formatLastSyncLine(lastSyncAt: Long?): String =
    if (lastSyncAt == null) "Never synced" else "Last synced ${formatRoundDate(lastSyncAt)}"

/** `null` while [SyncStatus.Idle] (nothing to report yet, so no result line at all), otherwise the text this screen's result line shows. */
private fun syncResultLine(status: SyncStatus): String? = when (status) {
    SyncStatus.Idle -> null
    SyncStatus.InProgress -> "Syncing…"
    is SyncStatus.Success -> status.message
    is SyncStatus.Failure -> status.message
}
