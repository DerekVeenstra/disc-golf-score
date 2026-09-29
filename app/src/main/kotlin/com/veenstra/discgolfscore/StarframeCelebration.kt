package com.veenstra.discgolfscore

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val STARFRAME_GREEN = Color(0xFF3DDC84)
private const val SOUND_BOOST_MILLIBELS = 800
private const val CELEBRATION_MILLIS = 2600L
private const val SPARKLE_COUNT = 8

/**
 * The short full-screen celebration shown between a starframe hole ([HoleScore.isStarframe]) and
 * whatever comes next — the next hole, or the final scoreboard. A green star springs in with a
 * burst of sparkles and an expanding ring while the JomezPro "starframe" sound plays, then
 * [onDone] fires on its own after [CELEBRATION_MILLIS]. Tapping anywhere skips straight to [onDone].
 * No haptic buzz — Derek's call; the sound and animation are the whole celebration.
 *
 * The sound is fire-and-forget ([playStarframeSound]): it isn't cut off when this screen leaves,
 * so skipping the animation doesn't also clip the audio.
 */
@Composable
fun StarframeCelebration(onDone: () -> Unit) {
    val context = LocalContext.current
    val latestOnDone by rememberUpdatedState(onDone)
    // The timer and a skip-tap can both land in the same frame; only the first may advance the
    // round, or a single starframe would skip a hole.
    val finished = remember { booleanArrayOf(false) }
    val currentOnDone = {
        if (!finished[0]) {
            finished[0] = true
            latestOnDone()
        }
    }

    val starScale = remember { Animatable(0f) }
    val starSpin = remember { Animatable(-180f) }
    val burst = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        playStarframeSound(context)
        coroutineScope {
            launch {
                starScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            }
            launch { starSpin.animateTo(0f, tween(700)) }
            launch { burst.animateTo(1f, tween(1100, easing = LinearEasing)) }
            launch {
                delay(350)
                textAlpha.animateTo(1f, tween(400))
            }
        }
        delay(CELEBRATION_MILLIS - 1100)
        currentOnDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { currentOnDone() },
        contentAlignment = Alignment.Center,
    ) {
        // Star + label are lifted together so the pair, not the star alone, sits at screen center.
        Box(
            modifier = Modifier.fillMaxSize().offset(y = (-16).dp),
            contentAlignment = Alignment.Center,
        ) {
            // Expanding ring that fades as it grows.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val progress = burst.value
                if (progress in 0.01f..0.99f) {
                    drawCircle(
                        color = STARFRAME_GREEN.copy(alpha = (1f - progress) * 0.7f),
                        radius = size.minDimension * (0.12f + 0.4f * progress),
                        style = Stroke(width = (6f * (1f - progress) + 1f).dp.toPx()),
                    )
                }
            }

            // Sparkles flying outward from the star.
            for (i in 0 until SPARKLE_COUNT) {
                val angle = (2 * Math.PI * i / SPARKLE_COUNT).toFloat()
                Text(
                    text = "✦",
                    fontSize = 14.sp,
                    color = STARFRAME_GREEN,
                    modifier = Modifier.graphicsLayer {
                        val distance = 80.dp.toPx() * burst.value
                        translationX = cos(angle) * distance
                        translationY = sin(angle) * distance
                        alpha = 1f - burst.value
                        rotationZ = burst.value * 180f
                    },
                )
            }

            // The star is drawn (not a font glyph, whose side bearings sit it off-center) and centered
            // on the screen; the label hangs below it without shifting it.
            Canvas(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = starScale.value
                        scaleY = starScale.value
                        rotationZ = starSpin.value
                    },
            ) {
                val outer = size.minDimension / 2f
                val inner = outer * 0.42f
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val a = (-Math.PI / 2 + i * Math.PI / 5).toFloat()
                    val pt = Offset(center.x + cos(a) * r, center.y + sin(a) * r)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                path.close()
                drawPath(path, STARFRAME_GREEN)
            }
            Text(
                text = "STARFRAME",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = STARFRAME_GREEN,
                modifier = Modifier
                    .offset(y = 62.dp)
                    .graphicsLayer { alpha = textAlpha.value },
            )
        }
    }
}

/**
 * Plays `res/raw/starframe.mp3` — the JomezPro starframe clip. It's their audio, so it's
 * gitignored rather than committed: it's looked up by name instead of as `R.raw.starframe` so a
 * fresh clone without the file still builds, and the celebration just runs silently.
 */
@SuppressLint("DiscouragedApi")
private fun playStarframeSound(context: Context) {
    val resId = context.resources.getIdentifier("starframe", "raw", context.packageName)
    if (resId == 0) return
    val player = MediaPlayer.create(
        context,
        resId,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId(),
    ) ?: return
    // MediaPlayer volume tops out at 1.0, so boost the clip's gain on its own audio session.
    val booster = runCatching {
        LoudnessEnhancer(player.audioSessionId).apply {
            setTargetGain(SOUND_BOOST_MILLIBELS)
            enabled = true
        }
    }.getOrNull()
    player.setOnCompletionListener {
        booster?.release()
        it.release()
    }
    player.start()
}
