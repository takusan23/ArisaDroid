package io.github.takusan23.arisadroid

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.view.Surface

// 参考にした AOSP のコード : https://cs.android.com/android/platform/superproject/+/master:cts/tests/tests/media/common/src/android/media/cts/InputSurface.java

/**
 * [Surface]とOpenGLを連携させるためのクラス
 * 本来 [android.opengl.GLSurfaceView] を利用することで EGL のセットアップを省略することができますが、
 * 今回は [android.view.SurfaceView] ではなく [Surface] だけで OpenGL を利用したいため、EGLのセットアップから記述している。
 */
class GLSurface(
    private val surface: Surface,
    private val renderer: CameraGLRenderer
) {
    private var mEGLDisplay = EGL14.EGL_NO_DISPLAY
    private var mEGLContext = EGL14.EGL_NO_CONTEXT
    private var mEGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglSetup()
    }

    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
     */
    private fun eglSetup() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }
        // Configure EGL for recording and OpenGL ES 2.0.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        checkEglError("eglCreateContext RGB888+recordable ES2")

        // Configure context for OpenGL ES 2.0.
        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        mEGLContext = EGL14.eglCreateContext(
            mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            attrib_list, 0
        )
        checkEglError("eglCreateContext")

        // Create a window surface, and attach it to the Surface we received.
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_NONE
        )
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
    }

    /** 描画する */
    fun drawFrame() {
        renderer.onDrawFrame()
    }

    /**
     * Discards all resources held by this class, notably the EGL context.
     */
    fun release() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface)
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEGLDisplay)
        }
        // surface.release() // GLは破棄しない
        mEGLDisplay = EGL14.EGL_NO_DISPLAY
        mEGLContext = EGL14.EGL_NO_CONTEXT
        mEGLSurface = EGL14.EGL_NO_SURFACE
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)
        checkEglError("eglMakeCurrent")
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     */
    fun swapBuffers(): Boolean {
        val result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)
        checkEglError("eglSwapBuffers")
        return result
    }

    /**
     * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
     */
    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs)
        checkEglError("eglPresentationTimeANDROID")
    }

    /**
     * Checks for EGL errors.  Throws an exception if one is found.
     */
    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error: 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
