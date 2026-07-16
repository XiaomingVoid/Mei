package com.ljyh.mei.ui.component.player.component

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ljyh.mei.constants.MeshFlowSpeedKey
import com.ljyh.mei.constants.MeshLowFreqVolumeKey
import com.ljyh.mei.constants.MeshPlayingKey
import com.ljyh.mei.constants.MeshStaticModeKey
import com.ljyh.mei.utils.audio.AudioVisualizerManager
import com.ljyh.mei.utils.rememberPreference
import com.skydoves.cloudy.cloudy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FluidBackground(
    imageUrl: String?,
    audioVisualizerManager: AudioVisualizerManager,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val density = LocalDensity.current
    
    // 监听音乐低频数值（用于节拍感应）
    val bass by audioVisualizerManager.bassValue.collectAsState()

    // 使用 by 属性委托，避免单变量解构产生的编译器类型推断歧义
    val flowSpeed by rememberPreference(MeshFlowSpeedKey, defaultValue = 0.25f)
    val staticMode by rememberPreference(MeshStaticModeKey, defaultValue = false)
    val meshPlaying by rememberPreference(MeshPlayingKey, defaultValue = true)
    val volumeScale by rememberPreference(MeshLowFreqVolumeKey, defaultValue = 0.1f)

    // 默认兜底种子颜色
    val defaultColors = remember(isDark) {
        if (isDark) {
            listOf(Color(0xFF2C1E4A), Color(0xFF52154E), Color(0xFF111135), Color(0xFF0F0F1A))
        } else {
            listOf(Color(0xFFE0C3FC), Color(0xFF8EC5FC), Color(0xFFE0E0E0), Color(0xFFFFFFFF))
        }
    }

    var fluidColors by remember { mutableStateOf(defaultColors) }

    // 1. 异步封面取色并进行 Apple Music 风格的高饱和色彩增强
    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrEmpty()) {
            fluidColors = defaultColors
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(200)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                fluidColors = extractVibrantColorsImproved(bitmap, isDark)
            }
        }
    }

    // 2. 颜色平滑过渡动画：当切歌或色彩改变时过渡，FastOutSlowInEasing 让色彩交融更有节奏感
    val animSpec = tween<Color>(durationMillis = 1500, easing = FastOutSlowInEasing)
    val c1 by animateColorAsState(fluidColors[0], animSpec, label = "c1")
    val c2 by animateColorAsState(fluidColors[1], animSpec, label = "c2")
    val c3 by animateColorAsState(fluidColors[2], animSpec, label = "c3")
    val c4 by animateColorAsState(fluidColors[3], animSpec, label = "c4")

    // 3. 动态流动时间累加器，当暂停播放、关闭动画或开启静态模式时自动静止
    var time by remember { mutableStateOf(0f) }
    val shouldAnimate = meshPlaying && isPlaying && !staticMode

    if (shouldAnimate) {
        LaunchedEffect(Unit) {
            var lastTime = System.nanoTime()
            while (true) {
                withFrameNanos { frameTime ->
                    val delta = (frameTime - lastTime) / 1_000_000_000f
                    lastTime = frameTime
                    // 基于 flowSpeed 偏好动态改变流动时间累加速度
                    time += delta * flowSpeed * 0.4f
                }
            }
        }
    }

    // 4. Android 12+ (SDK 31+) 硬件高斯模糊效果，在 1/10 低分辨率下渲染以达到 60fps 满帧
    val blurEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        remember(density) {
            val radius = with(density) { 15.dp.toPx() }
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else null

    Box(modifier = modifier.fillMaxSize()) {
        // 最底层底色，稳固整体基调
        val baseColor = if (isDark) c2.darken(0.6f) else Color(0xFFF0F0F0)
        Box(modifier = Modifier
            .fillMaxSize()
            .background(baseColor)
        )

        // 动画流体绘制层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurEffect != null) {
                        Modifier.graphicsLayer {
                            renderEffect = blurEffect
                            scaleX = 13f
                            scaleY = 13f
                        }
                    } else {
                        Modifier.cloudy(radius = 25)
                            .graphicsLayer {
                                scaleX = 1.3f
                                scaleY = 1.3f
                            }
                    }
                )
        ) {
            val drawModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurEffect != null) {
                Modifier.fillMaxSize(0.1f) // 1/10 缩微 Canvas
            } else {
                Modifier.fillMaxSize()
            }
            
            val colorsList = listOf(c1, c2, c3, c4)
            Canvas(modifier = drawModifier) {
                val w = size.width
                val h = size.height
                
                // 将低频节拍动态融合为物理缩放因数
                val pulse = bass * volumeScale

                // 圆圈 1：主色（左上移动）
                drawCircle(
                    color = colorsList[0].copy(alpha = 0.8f),
                    radius = w * (0.7f + pulse * 0.1f),
                    center = Offset(
                        w * (0.2f + 0.15f * sin(time * 0.7f)),
                        h * (0.3f + 0.1f * cos(time * 0.5f))
                    )
                )

                // 圆圈 2：辅助色（右下移动）
                drawCircle(
                    color = colorsList[1].copy(alpha = 0.8f),
                    radius = w * (0.8f + pulse * 0.05f),
                    center = Offset(
                        w * (0.8f - 0.2f * cos(time * 0.6f)),
                        h * (0.7f - 0.15f * sin(time * 0.8f))
                    )
                )

                // 圆圈 3：呼吸光斑（中间提亮）
                drawCircle(
                    color = colorsList[2].copy(alpha = 0.6f),
                    radius = w * (0.5f + pulse * 0.2f),
                    center = Offset(
                        w * (0.4f + 0.1f * sin(time * 0.9f)),
                        h * (0.5f + 0.12f * cos(time * 0.7f))
                    )
                )

                // 圆圈 4：强调色（右上移动）
                drawCircle(
                    color = colorsList[3].copy(alpha = 0.6f),
                    radius = w * (0.6f + pulse * 0.15f),
                    center = Offset(
                        w * (0.7f + 0.12f * cos(time * 0.8f)),
                        h * (0.2f + 0.1f * sin(time * 0.6f))
                    )
                )
            }
        }

        // 半透明暗部底护罩，保护前景歌词和操作控制栏的文字可读性
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.4f to Color.Black.copy(alpha = 0.1f),
                        1f to Color.Black.copy(alpha = 0.7f)
                    )
                )
        )
    }
}

/**
 * 提取鲜艳的高保真背景色彩 (设为 private 以避免与 AmbientBackground.kt 中同包下的公开顶层函数冲突)
 */
private fun extractVibrantColorsImproved(bitmap: Bitmap, isDark: Boolean): List<Color> {
    val palette = Palette.from(bitmap)
        .maximumColorCount(24)
        .generate()

    val vibrant = palette.vibrantSwatch
    val darkVibrant = palette.darkVibrantSwatch
    val lightVibrant = palette.lightVibrantSwatch
    val dominant = palette.dominantSwatch

    val seedSwatch = vibrant ?: dominant
    val seedColor = if (seedSwatch != null) {
        Color(seedSwatch.rgb)
    } else {
        if (isDark) Color(0xFF1A237E) else Color(0xFFE8EAF6)
    }

    var c1 = vibrant?.rgb?.let { Color(it) } ?: seedColor.boostSaturation(1.5f)
    var c2 = darkVibrant?.rgb?.let { Color(it) } ?: c1.darken(0.4f)
    var c3 = lightVibrant?.rgb?.let { Color(it) } ?: c1.lighten(0.3f)

    if (c1.isGrayscale()) c1 = c1.boostSaturation(3.0f).forceHueIfGray()
    if (c2.isGrayscale()) c2 = c2.boostSaturation(2.0f).forceHueIfGray()

    c1 = c1.boostSaturation(1.3f)
    c2 = c2.boostSaturation(1.3f)
    c3 = c3.boostSaturation(1.3f).lighten(0.1f)

    val c4 = c1.shiftHue(40f).lighten(0.1f)

    return listOf(c1, c2, c3, c4)
}

// --- 背景色彩饱和度、色相转换和亮度工具函数 (均标记为 private 局部扩展函数防止编译命名冲突) ---

private fun Color.isGrayscale(threshold: Float = 0.15f): Boolean {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    return hsl[1] < threshold
}

private fun Color.forceHueIfGray(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    if (hsl[1] < 0.05f) {
        hsl[0] = 240f
        hsl[1] = 0.5f
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.boostSaturation(multiplier: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    if (hsl[2] < 0.2f) hsl[2] = 0.2f
    hsl[1] = (hsl[1] * multiplier).coerceIn(0.2f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.darken(factor: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[2] = (hsl[2] * (1f - factor)).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.lighten(factor: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[2] = (hsl[2] + (1f - hsl[2]) * factor).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.shiftHue(amount: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[0] = (hsl[0] + amount).mod(360f)
    return Color(ColorUtils.HSLToColor(hsl))
}
