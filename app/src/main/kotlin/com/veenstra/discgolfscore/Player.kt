package com.veenstra.discgolfscore

/**
 * Strips control characters (tabs, newlines, and the rest of the U+0000-U+001F range, plus
 * U+007F) and trims surrounding whitespace. Every user-typed player/course name goes through this
 * before a [Player] or [Course] is constructed — see PLAN.md section 4 "Persistence": the
 * control-character codecs Phase 3 builds use two of those exact characters as field/record
 * separators, so a name that itself contained one would corrupt a saved record's framing. Rather
 * than escape just the two that matter, this guarantees up front that no name ever contains any
 * control character at all, the same call ultimate-score's `sanitizeName` made.
 *
 * Unlike ultimate-score's `TeamConfig.named`, this has no fallback: a blank result here means the
 * caller must reject the input rather than silently substitute a placeholder name (Phase 2 scope
 * per the plan — "It does not invent fallback names").
 */
internal fun sanitizeName(raw: String?): String = raw.orEmpty().filterNot { it.isISOControl() }.trim()

/**
 * Opaque white — the [Player.color] every `Player(id, name)` call that doesn't pass one gets,
 * including a legacy player record decoded from before [Player] had a color field at all
 * (see [decodePlayer][DiscGolfRepository.kt]'s 2-field branch). Rows treat it as "no color" and
 * stay plain (see [rowTint]); real colors are only assigned where a player is created by a person,
 * in [RoundViewModel.addPlayer].
 */
internal const val DEFAULT_PLAYER_COLOR: Long = 0xFFFFFFFF

/**
 * The colors new players are given, in the order [nextPlayerColor] hands them out. Packed ARGB
 * (`0xAARRGGBB`, the format [androidx.compose.ui.graphics.Color]'s `Long` constructor expects),
 * drawn at full opacity behind white text: saturated mid-tones that are easy to tell apart at a
 * glance in sunlight, each still dark enough for white text to stay readable (roughly 4:1 contrast
 * or better). The most distinct hues come first, so a typical foursome never gets two similar ones.
 */
internal val PLAYER_PALETTE: List<Long> = listOf(
    0xFF1E6FD9, // blue
    0xFFD32F2F, // red
    0xFF2E7D32, // green
    0xFFE65100, // orange
    0xFF7B1FA2, // purple
    0xFF00838F, // teal
    0xFFC2185B, // pink
    0xFFA66F00, // gold
    0xFF3949AB, // indigo
    0xFF558B2F, // olive
)

/**
 * The color for a new player, given the colors [taken] by players already saved: whichever
 * [PLAYER_PALETTE] color the fewest of them have, earliest in the palette on a tie. So no color
 * repeats until every palette color is in use, a deleted player's color is the first handed out
 * again, and past that the palette cycles evenly. Colors outside the palette (such as
 * [DEFAULT_PLAYER_COLOR]) don't use up a palette slot.
 */
internal fun nextPlayerColor(taken: List<Long>): Long =
    PLAYER_PALETTE.minBy { color -> taken.count { it == color } }

/** A saved, watch-local player who can be ticked onto a round. [color] fills their row's background (players screen, hole screen) — picked from [PLAYER_PALETTE] when the player is created ([RoundViewModel.addPlayer]) and kept for the player's lifetime. */
data class Player(val id: String, val name: String, val color: Long = DEFAULT_PLAYER_COLOR)
