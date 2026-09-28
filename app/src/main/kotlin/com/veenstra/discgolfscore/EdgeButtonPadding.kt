package com.veenstra.discgolfscore

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The other half of PLAN.md section 2's "Round-edge row safety" fix, alongside
 * `EdgeSafeTransform.kt`'s static [roundSafeWidth]: extra top/bottom content padding so a
 * `TransformingLazyColumn`'s first/last rows never scroll into the extreme top/bottom band of the
 * round face, where the inscribed circle narrows enough that even a horizontally-inset row can run
 * into the mask vertically. Where [roundSafeWidth] makes *width* safety a static, by-construction
 * property, this makes *how close to the edge a row can ever get* a static property too — both
 * halves of "static inset... with nothing to converge and no per-frame arithmetic to get wrong"
 * (PLAN.md section 2), as opposed to Phase 4's single per-row measurement trying to do both jobs
 * at once.
 */
private val ROUND_EDGE_INSET = 28.dp

/** [contentPadding] with [ROUND_EDGE_INSET] added to both its top and bottom insets — see [ROUND_EDGE_INSET]. */
@Composable
fun PaddingValues.withRoundEdgeInset(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding() + ROUND_EDGE_INSET,
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding() + ROUND_EDGE_INSET,
    )
}
