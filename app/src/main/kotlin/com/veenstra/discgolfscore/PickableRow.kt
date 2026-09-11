package com.veenstra.discgolfscore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
 * [tint], when supplied, replaces the usual plain white overlay with a translucent wash of that
 * color instead — a player's [Player.color] shown behind their name on the players screen.
 * `selected` still bumps the alpha up the same way it does for the untinted case, so a ticked/
 * radio-selected row still reads as more prominent than an unselected one even when tinted.
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
                    tint != null && selected -> tint.copy(alpha = 0.45f)
                    tint != null -> tint.copy(alpha = 0.28f)
                    selected -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
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
