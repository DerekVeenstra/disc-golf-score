package com.veenstra.discgolfscore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * The primary confirming action of a screen — a filled, accent-colored pill sitting in the
 * scrollable list rather than a persistent `EdgeButton` docked to the bottom of the screen
 * regardless of scroll position. Originally [HoleScreen]'s own `PrimaryActionRow` (Derek's call
 * there: a big always-visible button was taking up screen real estate even when nowhere near
 * ready to advance); pulled out here so every screen with a "Done"/"Save" action gets the same
 * inline treatment instead of a sticky bottom button.
 *
 * [enabled] defaults to true — every original caller (DONE/SAVE) is unconditionally actionable.
 * [NewRoundSetupScreen]'s START is the first caller that needs it false (course/layout/players
 * not all picked yet): a dimmed, unclickable pill rather than the near-invisible disabled
 * `EdgeButton` it replaced.
 */
@Composable
fun PrimaryActionRow(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        // Vertical padding first (outermost) so it's margin around the 48dp pill below, not
        // padding eating into the pill's own height and shrinking its tap target under 48dp.
        modifier = Modifier
            .padding(top = 6.dp, bottom = 2.dp)
            .roundSafeWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (enabled) 1f else 0.6f),
        )
    }
}
