package io.github.takusan23.arisadroid

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity(), SurfaceTexture.OnFrameAvailableListener {

    private val isPermissionGranted: Boolean
        get() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val surfaceView by lazy { SurfaceView(this) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.all { it.value }) {
            setup()
        }
    }

    /** 生成した [GLSurface] */
    private val glSurfaceList = arrayListOf<GLSurface>()

    /** 利用中の [CameraItem] */
    private val cameraItemList = arrayListOf<CameraItem>()

    /** プレビュー用に生成した [SurfaceTexture] */
    private val previewSurfaceTexture = arrayListOf<SurfaceTexture>()

    /** onFrameAvailable が最後に呼ばれた時間 */
    private var latestUpdateTime = 0L

    /** カメラ用スレッド */
    private var cameraJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(surfaceView)

        if (!isPermissionGranted) {
            permissionRequest.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
        }
    }


    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // 更新を通知するため、値を更新する
        latestUpdateTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (isPermissionGranted) {
            setup()
        }
    }

    override fun onPause() {
        super.onPause()
        // リソース開放
        cameraJob?.cancel()
        glSurfaceList.forEach { it.release() }
        glSurfaceList.clear()
        previewSurfaceTexture.forEach { it.release() }
        previewSurfaceTexture.clear()
        cameraItemList.forEach { it.destroy() }
        cameraItemList.clear()
    }

    private fun setup() {
        cameraJob = lifecycleScope.launch(Dispatchers.IO) {
            // SurfaceView を待つ
            val previewSurface = waitSurface()

            // CameraRenderer を作る
            val cameraGLRenderer = CameraGLRenderer(
                rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90f else 0f, // 画面回転
                mainSurfaceTexture = { previewSurfaceTexture[0] },
                subSurfaceTexture = { previewSurfaceTexture[1] }
            )
            // GLSurface を作る
            val glSurface = GLSurface(
                surface = previewSurface,
                renderer = cameraGLRenderer,
            )
            glSurface.makeCurrent()
            glSurfaceList += glSurface

            // プレビューで利用する SurfaceTexture を用意
            // SurfaceTexture の場合は setDefaultBufferSize で解像度の設定ができる
            val previewSurfaceTexturePair = cameraGLRenderer.setupProgram().let { (mainCameraTextureId, subCameraTextureId) ->
                // メイン映像
                val main = SurfaceTexture(mainCameraTextureId).apply {
                    setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    setOnFrameAvailableListener(this@MainActivity)
                }
                // サブ映像
                val sub = SurfaceTexture(subCameraTextureId).apply {
                    setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    setOnFrameAvailableListener(this@MainActivity)
                }
                main to sub
            }
            previewSurfaceTexture.addAll(previewSurfaceTexturePair.toList())

            // どっちのカメラをメイン映像にするか
            // 今回はメイン映像をバックカメラ、サブ映像（ワイプ）をフロントカメラに指定
            val previewMainSurfaceTexture = previewSurfaceTexturePair.first
            val previewSubSurfaceTexture = previewSurfaceTexturePair.second
            // カメラを開く
            val (backCameraId, frontCameraId) = CameraTool.getCameraId(this@MainActivity)
            cameraItemList += CameraItem(this@MainActivity, backCameraId, Surface(previewMainSurfaceTexture))
            cameraItemList += CameraItem(this@MainActivity, frontCameraId, Surface(previewSubSurfaceTexture))
            cameraItemList.forEach { it.openCamera() }
            // プレビューする
            cameraItemList.forEach { it.startPreview() }

            // OpenGL のレンダリングを行う
            // ここで行う理由ですが、makeCurrent したスレッドでないと glDrawArray できない？ + onFrameAvailable が UIスレッド なので重たいことはできないためです。
            // ただ、レンダリングするタイミングは onFrameAvailable が更新されたタイミングなので、
            // while ループを回して 新しいフレームが来ているか確認しています。
            var prevUpdateTime = 0L
            while (isActive) {
                if (latestUpdateTime != prevUpdateTime) {
                    glSurfaceList.forEach {
                        it.drawFrame()
                        it.swapBuffers()
                    }
                    prevUpdateTime = latestUpdateTime
                }
            }
        }
    }

    /** Surface の用意が終わるまで一時停止する */
    private suspend fun waitSurface() = suspendCoroutine { continuation ->
        surfaceView.holder.apply {
            if (surface.isValid) {
                continuation.resume(this.surface)
            } else {
                var callback: SurfaceHolder.Callback? = null
                callback = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        continuation.resume(holder.surface)
                        removeCallback(callback)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // do nothing
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // do nothing
                    }
                }
                addCallback(callback)
            }
        }
    }

    companion object {

        /** 720P 解像度 幅 */
        private const val CAMERA_RESOLTION_WIDTH = 1280

        /** 720P 解像度 高さ */
        private const val CAMERA_RESOLTION_HEIGHT = 720

    }

}
