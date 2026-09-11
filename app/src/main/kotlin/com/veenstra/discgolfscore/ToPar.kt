package com.veenstra.discgolfscore

/**
 * Formats a to-par total the way disc golf scorecards do: `E` for even, a leading `+` for over,
 * and the same minus-sign glyph [StepperRow] already uses (`−`, U+2212) for under — PLAN.md
 * section 3's "Hole" mockup shows exactly this shape in the standings block (`+2`, `E`, `−1`).
 * Shared by the hole screen's live standings and the finished-round placeholder's final totals, so
 * the two screens can't drift on formatting.
 */
internal fun formatToPar(toPar: Int): String = when {
    toPar == 0 -> "E"
    toPar > 0 -> "+$toPar"
    else -> "−${-toPar}"
}
