package com.treko.assistant.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.core.common.model.TrekoEmotion

/**
 * Componente interactivo que renderiza la cara reactiva de Treko mediante los GIFs oficiales.
 */
@Composable
fun TrekoFace(
    emotion: TrekoEmotion,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp
) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    val assetPath = "file:///android_asset/gifs/${emotion.fileName}"

    Box(
        modifier = modifier
            .size(size)
            .shadow(16.dp, CircleShape, spotColor = NeonCyan)
            .clip(CircleShape)
            .background(SurfaceDark)
            .border(2.dp, CardBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = assetPath, animationSpec = tween(250), label = "EmotionCrossfade") { path ->
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
