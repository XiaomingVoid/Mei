package com.ljyh.mei.ui.component.player.component

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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

    // 绑定与原 OpenGL 网格背景完全相同的外观设置 Key
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

    // 1. 异步封面取色并调用同包下已经对外公开的 extractVibrantColorsImproved 方法
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

    // 2. 颜色平滑过渡动画
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
        // 最底层底色，直接调用同包下的 Color.darken 扩展函数
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
