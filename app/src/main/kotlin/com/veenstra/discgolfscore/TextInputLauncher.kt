package com.veenstra.discgolfscore

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.wear.input.RemoteInputIntentHelper

/** Key the typed text comes back under from the Wear text-input activity. */
private const val TEXT_INPUT_KEY = "typed_text"

/**
 * One reusable wrapper around Wear's standard `RemoteInputIntentHelper` text-entry activity
 * (keyboard *and* voice dictation) — lifted verbatim from ultimate-score's
 * `NewGameSetupScreen.rememberTextInputLauncher` (PLAN.md's own pointer to that file). [onResult]
 * gets `null` for a cancelled or otherwise non-OK result; every call site here treats that the
 * same as an empty typed string — "nothing usable came back, so do nothing."
 *
 * The returned launcher takes a [String]`?` `prefill`, passed non-null for a rename (the row's
 * current name) and `null` for a "+ New…" row with nothing to prefill. `RemoteInput` has no
 * "default text" extra for freeform input — the Wear idiom for a prefilled, editable box is to
 * offer the existing value as its one `choice` with `setEditChoicesBeforeSending` enabled, so
 * tapping it opens the keyboard already loaded with that text instead of blank.
 */
@Composable
fun rememberTextInputLauncher(label: String, onResult: (String?) -> Unit): (prefill: String?) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val typed = if (result.resultCode == Activity.RESULT_OK) {
            RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(TEXT_INPUT_KEY)?.toString()
        } else {
            null
        }
        onResult(typed)
    }
    return { prefill ->
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val inputBuilder = RemoteInput.Builder(TEXT_INPUT_KEY).setLabel(label)
        if (!prefill.isNullOrBlank()) {
            inputBuilder
                .setChoices(arrayOf(prefill))
                .setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED)
        }
        val inputs = listOf(inputBuilder.build())
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, inputs)
        launcher.launch(intent)
    }
}
