package com.veenstra.discgolfscore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** Matches [StepperRow]'s minimum target size — every tappable row on these lists is at least 48dp tall (PLAN.md section 3). */
private val ROW_HEIGHT = 48.dp

/** A row with a [PickableRow] `detail` line under its label. */
private val TWO_LINE_ROW_HEIGHT = 56.dp

/**
 * [Player.color] as a row background (`tint` on [PickableRow]/[StepperRow]), or `null` for a plain
 * row when the player has no color of their own: one saved before players had colors decodes to
 * [DEFAULT_PLAYER_COLOR], white, and a solid white row would hide the player's white name.
 */
internal fun Player.rowTint(): Color? = if (color == DEFAULT_PLAYER_COLOR) null else Color(color)

/**
 * One row of a pick list: course/player selection on the new-round setup screen, and the plain
 * roster rows on the players/courses managers. `selected` drives the highlight; [leading] is the
 * radio/checkbox glyph (`●`/`○` for single-select, `☑`/`☐` for multi-select) or `null` for a
 * plain "+ New…" action row, which has nothing to select. [detail], when given, is a smaller second
 * line under [label] (a past round's date), and makes the row a little taller to fit it.
 *
 * Tap always fires [onClick]. When [onLongClick] is supplied the row also responds to a long
 * press — the manage gesture PLAN.md section 3 asks for ("Long-press any course or player row →
 * rename, edit pars (courses), or delete"), lifted from ultimate-score's `SelectableRow`
 * (PLAN.md's own pointer, section 13's `combinedClickable`). Width comes from [roundSafeWidth]
 * rather than a fixed inset — see that function's doc for why a static percentage isn't enough.
 *
 * [tint], when supplied, replaces the usual plain white overlay with that color at full opacity —
 * a player's [Player.color] shown behind their name on the players screen. (An earlier translucent
 * wash over the black background came out nearly black.) Since a tinted row's fill can't also
 * brighten to show `selected`, a selected one gets a white outline instead — the new-round setup
 * screen's ticked players. An outline, not a dimmed unselected row: dimness is exactly what
 * sunlight erases (PLAN.md section 2 "Untouched rows").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickableRow(
    label: String,
    selected: Boolean,
    leading: String? = null,
    tint: Color? = null,
    detail: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .roundSafeWidth()
            .height(if (detail == null) ROW_HEIGHT else TWO_LINE_ROW_HEIGHT)
            .clip(RoundedCornerShape(24.dp))
            .background(
                when {
                    tint != null -> tint
                    selected -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .then(if (tint != null && selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(24.dp)) else Modifier)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(text = leading, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
            }
            Column {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
