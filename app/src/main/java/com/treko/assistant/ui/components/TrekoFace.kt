package com.treko.assistant.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.model.TrekoState
import kotlin.math.PI
import kotlin.math.sin

private object TrekoGifImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .crossfade(true)
                .build()
                .also { instance = it }
        }
    }
}

/**
 * Expressive companion avatar with:
 * - Shared singleton Coil GIF ImageLoader (no leaks across tab/history transitions)
 * - Spring-physics state & size transitions
 * - Zero-recomposition graphicsLayer breathing, micro-saccade tilt, and audio-reactive scale
 * - Dynamic Canvas aura / sonar pulse / thinking orbital ring via drawWithCache
 */
@Composable
fun TrekoFace(
    emotion: TrekoEmotion,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    state: TrekoState = TrekoState.IDLE,
    audioLevelState: State<AudioLevel>? = null
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { TrekoGifImageLoader.get(context) }
    val fallbackAudioState = remember { mutableStateOf(AudioLevel()) }
    val activeAudioState = audioLevelState ?: fallbackAudioState

    val animatedSize by animateDpAsState(
        targetValue = size,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "FaceSizeSpring"
    )

    val targetRingColor = when (state) {
        TrekoState.IDLE -> if (emotion == TrekoEmotion.SLEEPY) NeonPurple.copy(alpha = 0.45f) else NeonCyan.copy(alpha = 0.55f)
        TrekoState.LISTENING -> NeonCyan
        TrekoState.THINKING -> NeonPurple
        TrekoState.SPEAKING -> NeonCyan
        TrekoState.ERROR -> Color(0xFFFF5252)
    }
    val ringColor by animateColorAsState(
        targetValue = targetRingColor,
        animationSpec = tween(320),
        label = "RingColorAnim"
    )

    val stateScaleTarget = when (state) {
        TrekoState.LISTENING -> 1.035f
        TrekoState.SPEAKING -> 1.045f
        TrekoState.THINKING -> 0.985f
        TrekoState.ERROR -> 0.97f
        TrekoState.IDLE -> if (emotion == TrekoEmotion.SLEEPY) 0.96f else 1.0f
    }
    val stateScale by animateFloatAsState(
        targetValue = stateScaleTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "StateScaleSpring"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "TrekoLivingMotion")

    // Continuous phase [0..2pi] for organic breathing & micro-saccades
    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "BreathPhase"
    )

    // Slower phase for subtle gaze/head micro-saccades
    val saccadePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SaccadePhase"
    )

    // Orbital sweep angle for THINKING state and listening sonar pulse
    val orbitalAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbitalAngle"
    )

    val sonarProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SonarProgress"
    )

    val assetPath = "file:///android_asset/gifs/${emotion.fileName}"

    // Outer aura container adds breathing room for glow & rings without clipping
    Box(
        modifier = modifier
            .size(animatedSize + 28.dp)
            .drawWithCache {
                val strokeBase = 3.dp.toPx()
                val glowStroke = 8.dp.toPx()

                onDrawBehind {
                    val minDim = this.size.minDimension
                    val padPx = 28.dp.toPx()
                    if (minDim <= padPx) return@onDrawBehind

                    val rms = activeAudioState.value.rms
                    val rmsFactor = (rms * 4.5f).coerceIn(0f, 1f)
                    val radius = ((minDim - padPx) / 2f).coerceAtLeast(1f)
                    val center = this.center

                    // Soft radial aura behind avatar
                    val auraRadius = (radius + 14.dp.toPx() * (0.4f + rmsFactor * 0.6f)).coerceAtLeast(1f)
                    val auraAlpha = when (state) {
                        TrekoState.SPEAKING -> 0.25f + rmsFactor * 0.35f
                        TrekoState.LISTENING -> 0.22f + rmsFactor * 0.30f
                        TrekoState.THINKING -> 0.26f
                        TrekoState.ERROR -> 0.25f
                        TrekoState.IDLE -> 0.14f + 0.06f * sin(breathPhase)
                    }.coerceIn(0.05f, 0.7f)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ringColor.copy(alpha = auraAlpha),
                                ringColor.copy(alpha = auraAlpha * 0.35f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = auraRadius
                        ),
                        radius = auraRadius,
                        center = center
                    )

                    // Expanding sonar ring when LISTENING or SPEAKING
                    if (state == TrekoState.LISTENING || state == TrekoState.SPEAKING) {
                        val pulseRadius = radius + (12.dp.toPx() * sonarProgress) + (rmsFactor * 8.dp.toPx())
                        val pulseAlpha = ((1f - sonarProgress) * (0.42f + rmsFactor * 0.35f)).coerceIn(0f, 0.8f)
                        drawCircle(
                            color = ringColor.copy(alpha = pulseAlpha),
                            radius = pulseRadius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // Dual orbital arc when THINKING
                    if (state == TrekoState.THINKING) {
                        val arcOffset = 5.dp.toPx()
                        val topLeft = androidx.compose.ui.geometry.Offset(
                            center.x - radius - arcOffset,
                            center.y - radius - arcOffset
                        )
                        val arcSize = androidx.compose.ui.geometry.Size(
                            (radius + arcOffset) * 2f,
                            (radius + arcOffset) * 2f
                        )
                        drawArc(
                            color = NeonCyan,
                            startAngle = orbitalAngle,
                            sweepAngle = 75f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeBase, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = NeonPurple,
                            startAngle = orbitalAngle + 180f,
                            sweepAngle = 75f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeBase, cap = StrokeCap.Round)
                        )
                    }

                    // Reactive border glow ring
                    drawCircle(
                        color = ringColor.copy(alpha = (0.35f + rmsFactor * 0.5f).coerceIn(0.2f, 0.95f)),
                        radius = radius + 2.dp.toPx(),
                        center = center,
                        style = Stroke(width = if (state == TrekoState.SPEAKING) glowStroke * (0.4f + rmsFactor * 0.6f) else strokeBase)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(animatedSize)
                .graphicsLayer {
                    val rmsFactor = (activeAudioState.value.rms * 4.0f).coerceIn(0f, 1f)
                    val breathAmp = if (emotion == TrekoEmotion.SLEEPY) 0.018f else 0.012f
                    val breathScale = 1f + breathAmp * sin(breathPhase)
                    val audioPunch = when (state) {
                        TrekoState.SPEAKING -> rmsFactor * 0.055f
                        TrekoState.LISTENING -> rmsFactor * 0.030f
                        else -> 0f
                    }
                    val totalScale = stateScale * breathScale + audioPunch
                    scaleX = totalScale
                    scaleY = totalScale

                    // Subtle organic floating bob & head tilt
                    val bobPx = when (state) {
                        TrekoState.SPEAKING -> sin(breathPhase * 2f) * 3.dp.toPx()
                        TrekoState.THINKING -> sin(breathPhase) * 2.5.dp.toPx()
                        else -> sin(breathPhase) * 3.5.dp.toPx()
                    }
                    translationY = bobPx
                    translationX = when (state) {
                        TrekoState.IDLE -> sin(saccadePhase) * 1.8.dp.toPx()
                        TrekoState.THINKING -> sin(saccadePhase * 1.5f) * 2.5.dp.toPx()
                        else -> 0f
                    }
                    rotationZ = when (state) {
                        TrekoState.LISTENING -> -1.8f + 0.7f * sin(saccadePhase)
                        TrekoState.THINKING -> 1.4f * sin(saccadePhase * 1.4f)
                        TrekoState.SPEAKING -> 0.8f * sin(breathPhase * 2f)
                        TrekoState.ERROR -> 1.5f * sin(breathPhase * 4f)
                        TrekoState.IDLE -> 0.9f * sin(saccadePhase)
                    }
                }
                .clip(CircleShape)
                .background(SurfaceDark)
                .border(2.dp, if (state == TrekoState.IDLE) CardBorder else ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = assetPath,
                animationSpec = tween(220),
                label = "EmotionCrossfade"
            ) { path ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(path)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "Treko Face: ${emotion.name}",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
