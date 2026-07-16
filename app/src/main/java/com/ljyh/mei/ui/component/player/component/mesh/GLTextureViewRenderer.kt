package com.ljyh.mei.ui.component.player.component.mesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import android.view.TextureView
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GLTextureViewRenderer"

/**
 * TextureView-based GL renderer that replaces GLSurfaceView.
 *
 * GLSurfaceView uses a separate SurfaceView layer (hole-punch compositing), which conflicts
 * with Compose's graphicsLayer/clip/offset modifiers, especially on Android 16 with
 * Immortalis-G720 GPU drivers (Dimensity 9300+).
 *
 * TextureView renders into the standard View hierarchy, so it composes correctly with
 * Compose's AndroidView wrapper.
 */
class MeshBackgroundTextureView(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener {

    private val renderer = MeshGradientRenderer()
    private var renderThread: RenderThread? = null
    private val isAttached = AtomicBoolean(false)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setAlbum(bitmap: Bitmap) {
        renderThread?.queueEvent { renderer.setAlbum(bitmap) }
    }

    fun updateVolume(v: Float) {
        renderer.volume = v
    }

    fun setFlowSpeed(speed: Float) {
        renderer.flowSpeed = speed
    }

    fun setRenderScale(scale: Float) {
        renderer.renderScale = scale
        renderThread?.queueEvent { renderer.rebuildFbo() }
    }

    fun setSubdivision(level: Int) {
        renderer.subdivision = level
    }

    fun setStaticMode(enable: Boolean) {
        renderThread?.queueEvent { renderer.setStaticMode(enable) }
    }

    fun setPlaying(playing: Boolean) {
        renderer.setPlaying(playing)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Timber.tag(TAG).d("Surface available: ${width}x${height}")
        renderThread = RenderThread(surface, width, height, renderer).also {
            it.start()
        }
        isAttached.set(true)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Timber.tag(TAG).d("Surface size changed: ${width}x${height}")
        renderThread?.onSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Timber.tag(TAG).d("Surface destroyed")
        isAttached.set(false)
        renderThread?.stopRendering()
        renderThread = null
        // Return true so the system releases the SurfaceTexture
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // No-op, we manage updates ourselves
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached.set(false)
        renderThread?.stopRendering()
        renderThread = null
    }

    /**
     * Dedicated GL rendering thread with manual EGL context management.
     */
    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        private val renderer: MeshGradientRenderer
    ) : Thread("MeshRenderThread") {

        private val running = AtomicBoolean(true)
        private val eventQueue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        @Volatile
        private var sizeChanged = false

        @Volatile
        private var newWidth = 0

        @Volatile
        private var newHeight = 0

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun queueEvent(runnable: Runnable) {
            eventQueue.add(runnable)
        }

        fun onSizeChanged(w: Int, h: Int) {
            newWidth = w
            newHeight = h
            sizeChanged = true
        }

        fun stopRendering() {
            running.set(false)
            try {
                join(2000)
            } catch (e: InterruptedException) {
                Timber.tag(TAG).w(e, "Interrupted while waiting for render thread to stop")
            }
        }

        override fun run() {
            try {
                initEGL()
                renderer.onSurfaceCreated(null, null)
                renderer.onSurfaceChanged(null, width, height)

                while (running.get()) {
                    // Process queued events
                    var event = eventQueue.poll()
                    while (event != null) {
                        event.run()
                        event = eventQueue.poll()
                    }

                    // Handle size changes
                    if (sizeChanged) {
                        sizeChanged = false
                        width = newWidth
                        height = newHeight
                        renderer.onSurfaceChanged(null, width, height)
                    }

                    // Render frame
                    renderer.onDrawFrame(null)

                    // Swap buffers
                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                        val error = EGL14.eglGetError()
                        if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) {
                            Timber.tag(TAG).w("EGL surface lost (error=$error), stopping render thread")
                            break
                        }
                        Timber.tag(TAG).w("eglSwapBuffers failed: $error")
                    }

                    // Target ~60fps
                    try {
                        sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Render thread error")
            } finally {
                renderer.release()
                destroyEGL()
            }
        }

        private fun initEGL() {
            // 1. Get display
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("eglGetDisplay failed: ${EGL14.eglGetError()}")
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed: ${EGL14.eglGetError()}")
            }
            Timber.tag(TAG).d("EGL initialized: ${version[0]}.${version[1]}")

            // 2. Choose config - request RGBA8888 with OpenGL ES 3.0
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x0040, // EGL_OPENGL_ES3_BIT
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                throw RuntimeException("eglChooseConfig failed: ${EGL14.eglGetError()}")
            }
            if (numConfigs[0] == 0) {
                throw RuntimeException("No EGL configs found")
            }
            val eglConfig = configs[0]!!

            // 3. Create context (OpenGL ES 3.0)
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("eglCreateContext failed: ${EGL14.eglGetError()}")
            }

            // 4. Create window surface from SurfaceTexture
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val surface = Surface(surfaceTexture)
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, surfaceAttribs, 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            }

            // 5. Make current
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
            }

            Timber.tag(TAG).d("EGL context created successfully")
        }

        private fun destroyEGL() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            Timber.tag(TAG).d("EGL destroyed")
        }
    }
}
