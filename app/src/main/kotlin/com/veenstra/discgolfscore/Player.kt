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
 * (see [decodePlayer][DiscGolfRepository.kt]'s 2-field branch). Actual random assignment only
 * happens where a player is created by a person, in [RoundViewModel.addPlayer].
 */
internal const val DEFAULT_PLAYER_COLOR: Long = 0xFFFFFFFF

/**
 * A random packed ARGB color (`0xAARRGGBB`, the format [androidx.compose.ui.graphics.Color]'s
 * `Long` constructor expects) for a newly created player's row — shown on the players screen and
 * as the background of their row on the hole screen while scoring. Hue is uniform-random across
 * the full wheel so successive players read as visibly distinct; saturation and lightness are
 * fixed at values chosen to stay legible as a tinted row background behind this app's white text
 * on its black theme (PLAN.md's black-and-white base) — saturated enough to read as a color,
 * dark enough that full-opacity white text never washes out against it.
 */
internal fun randomPlayerColor(): Long = hslToPackedArgb(hueDegrees = kotlin.random.Random.nextInt(360))

private fun hslToPackedArgb(hueDegrees: Int, saturation: Float = 0.55f, lightness: Float = 0.35f): Long {
    val h = hueDegrees / 360f
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((h * 6f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r1, g1, b1) = when {
        h < 1f / 6f -> Triple(c, x, 0f)
        h < 2f / 6f -> Triple(x, c, 0f)
        h < 3f / 6f -> Triple(0f, c, x)
        h < 4f / 6f -> Triple(0f, x, c)
        h < 5f / 6f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255).toLong()
    return (0xFFL shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}

/** A saved, watch-local player who can be ticked onto a round. [color] tints their row's background (players screen, hole screen) — assigned once, randomly, when the player is created ([RoundViewModel.addPlayer]) and kept for the player's lifetime. */
data class Player(val id: String, val name: String, val color: Long = DEFAULT_PLAYER_COLOR)
