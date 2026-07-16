# 播放器 Apple Music 流体背景重构计划

## 目标与背景

目前播放器使用的背景是基于 OpenGL ES 3.0 的网格渐变背景（`MeshBackgroundTextureView`）。虽然它还原了 AMLL (Apple Music Like Lyrics) 的网格扭曲算法，但在 Android 设备上面临着严峻的 GPU 兼容性挑战：

1. 在特定处理器设备（如天玑、Mali 系列 GPU）以及 Android 16 beta 版本上，容易出现黑屏、闪退或图形上下文丢失。
2. OpenGL Context 频繁创建 and 销毁导致明显的开销和偶发内存泄露。
3. 渲染管线繁重，对中低端设备造成较大的 GPU 负荷。

为了提供稳定、高还原度、流畅且低能耗的 Apple Music 流体背景体验，我们提议将背景重构为基于 **Jetpack Compose Canvas + 软/硬高斯模糊拉伸** 的实现方案。该方案在设计原理上完全对齐 AMLL 的网页端色彩渲染与动画混合模式，且能够 100% 避免任何底层的 GPU 驱动闪退。

---

## User Review Required

> [!IMPORTANT]
> **关于底层 OpenGL 代码的处置：**
> 本次更改会将 `FluidBackground.kt` 重构为基于 Compose Canvas 的高性能流体背景。原有的 `com.ljyh.mei.ui.component.player.component.mesh` 目录下的 OpenGL 渲染代码（包括 `MeshGradientRenderer.kt`, `BHPMesh.kt` 等）在替换后将不再有任何地方引用。我们计划将其删除以精简代码库。如果您希望保留它们以作备份，请在批准前告知。

---

## Open Questions

暂无。当前方案在保留外观设置里所有控制功能（动态流速、节拍灵敏度、播放/暂停、静态模式）的前提下，提高了兼容性和流畅度。

---

## Proposed Changes

### 播放器背景组件

#### [MODIFY] [FluidBackground.kt](file:///d:/GitHub/mei/Mei/app/src/main/java/com/ljyh/mei/ui/component/player/component/FluidBackground.kt)

- 完全重写该文件，移除对 `MeshBackgroundTextureView` 的 `AndroidView` 封装。
- 引入纯 Compose Canvas 实现的 4 色动态流体绘制逻辑，并使用协程和 `withFrameNanos` 实现精确、流畅、可调整的动画时间驱动。
- 对接 `AudioVisualizerManager`，将节奏节拍（`bass` 和 `volumeScale`）动态融合进流体圆圈的膨胀律动中。
- 保留 `flowSpeed`, `meshPlaying`, `staticMode` 等参数的支持。
- 在 Android 12+ (SDK 31+) 上，通过 Canvas 在 1/10 缩略尺寸下绘制 4 个动画彩色圆圈，使用 `RenderEffect.createBlurEffect` 进行小半径模糊，并大倍数拉伸（如 13 倍）覆盖全屏，获得极致的高性能模糊效果。
- 在低于 Android 12 的版本上，使用 `com.skydoves.cloudy` 框架提供的软模糊算法进行平滑的模糊渲染。

#### [DELETE] [mesh 目录](file:///d:/GitHub/mei/Mei/app/src/main/java/com/ljyh/mei/ui/component/player/component/mesh)

- 删除 `BHPMesh.kt`, `ControlPoint.kt`, `ControlPointGenerator.kt`, `GLTextureViewRenderer.kt`, `MeshGradientRenderer.kt`, `ShaderSource.kt`, `AlbumTextureProcessor.kt`，彻底精简不再使用的 OpenGL 实现。

---

## Verification Plan

### Automated Tests

- 本项目暂无针对 UI 背景的单元测试。我们将确保 Gradle 构建成功，无编译错误：

  ```bash
  ./gradlew assembleDebug
  ```

### Manual Verification

1. 打开播放器，切换不同歌曲，检查背景是否能够根据专辑封面正确提取 Apple Music 风格的鲜艳色彩并呈现柔和渐变。
2. 观察背景圆圈是否能随音乐平滑流动。
3. 调大/调小“流动速度”、“节拍灵敏度”，验证其控制表现是否灵敏。
4. 暂停音乐或开启“静态模式”，检查背景动画是否能够平稳停下以节省能耗。
