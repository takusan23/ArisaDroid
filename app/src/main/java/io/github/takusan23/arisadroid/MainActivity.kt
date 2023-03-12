package io.github.takusan23.arisadroid

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.contentValuesOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    /** 生成した [SurfaceTexture] */
    private val previewSurfaceTexture = arrayListOf<SurfaceTexture>()

    /** onFrameAvailable が最後に呼ばれた時間 */
    private var latestUpdateTime = 0L

    /** カメラ用スレッド */
    private var cameraJob: Job? = null

    /** 静止画撮影  */
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // これ
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setDecorFitsSystemWindows(false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        setContent {
            Box(
                modifier = Modifier
                    .background(Color.Black)
                    .fillMaxSize()
            ) {
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        // 16:9 のアスペクト比にする
                        .aspectRatio(
                            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                CAMERA_RESOLTION_WIDTH.toFloat() / CAMERA_RESOLTION_HEIGHT.toFloat()
                            } else {
                                CAMERA_RESOLTION_HEIGHT.toFloat() / CAMERA_RESOLTION_WIDTH.toFloat()
                            }
                        ),
                    factory = { surfaceView }
                )
                Button(
                    modifier = Modifier
                        .padding(bottom = 30.dp)
                        .align(Alignment.BottomCenter),
                    onClick = { capture() }
                ) { Text(text = "撮影する") }
            }
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
        } else {
            permissionRequest.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onPause() {
        super.onPause()
        // リソース開放
        cameraJob?.cancel()
        previewSurfaceTexture.forEach {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
        imageReader?.close()
        previewSurfaceTexture.clear()
        glSurfaceList.forEach { it.release() }
        glSurfaceList.clear()
        cameraItemList.forEach { it.destroy() }
        cameraItemList.clear()
    }

    private fun setup() {
        cameraJob = lifecycleScope.launch(Dispatchers.IO) {
            // SurfaceView を待つ
            val previewSurface = waitSurface()
            // 静止画撮影で利用する ImageReader
            // Surface の入力から画像を生成できる
            val imageReader = ImageReader.newInstance(
                CAMERA_RESOLTION_WIDTH,
                CAMERA_RESOLTION_HEIGHT,
                PixelFormat.RGBA_8888,
                2
            )
            this@MainActivity.imageReader = imageReader

            // CameraRenderer を作る
            val previewCameraGLRenderer = CameraGLRenderer(
                rotation = if (isLandscape) 90f else 0f, // 画面回転
                mainSurfaceTexture = { previewSurfaceTexture[0] },
                subSurfaceTexture = { previewSurfaceTexture[1] }
            )
            val captureCameraGLRenderer = CameraGLRenderer(
                rotation = if (isLandscape) 90f else 0f, // 画面回転
                mainSurfaceTexture = { previewSurfaceTexture[2] },
                subSurfaceTexture = { previewSurfaceTexture[3] }
            )
            // GLSurface を作る
            val previewGlSurface = GLSurface(
                surface = previewSurface,
                renderer = previewCameraGLRenderer,
            )
            val captureGlSurface = GLSurface(
                surface = imageReader.surface,
                renderer = captureCameraGLRenderer
            )
            glSurfaceList += previewGlSurface
            glSurfaceList += captureGlSurface

            // プレビュー / 静止画撮影 で利用する SurfaceTexture を用意
            // SurfaceTexture の場合は setDefaultBufferSize でカメラの解像度の設定ができる (720P など)
            previewGlSurface.makeCurrent()
            val previewSurfaceTexturePair = previewCameraGLRenderer.setupProgram().let { (mainCameraTextureId, subCameraTextureId) ->
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
            captureGlSurface.makeCurrent()
            val captureSurfaceTexturePair = captureCameraGLRenderer.setupProgram().let { (mainCameraTextureId, subCameraTextureId) ->
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
            previewSurfaceTexture.addAll(captureSurfaceTexturePair.toList())

            // どっちのカメラをメイン映像にするか
            // 今回はメイン映像をバックカメラ、サブ映像（ワイプ）をフロントカメラに指定
            // Pair は メイン映像に指定する SurfaceTexture のリスト
            val mainSurfaceTexture = listOf(previewSurfaceTexturePair.first, captureSurfaceTexturePair.first)
            val subSurfaceTexture = listOf(previewSurfaceTexturePair.second, captureSurfaceTexturePair.second)

            // カメラを開く
            val (backCameraId, frontCameraId) = CameraTool.getCameraId(this@MainActivity)
            cameraItemList += CameraItem(this@MainActivity, backCameraId, Surface(mainSurfaceTexture[0]), Surface(mainSurfaceTexture[1]))
            cameraItemList += CameraItem(this@MainActivity, frontCameraId, Surface(subSurfaceTexture[0]), Surface(subSurfaceTexture[1]))
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
                        it.makeCurrent() // 多分いる
                        it.drawFrame()
                        it.swapBuffers()
                    }
                    prevUpdateTime = latestUpdateTime
                }
            }
        }
    }

    /** [imageReader]から取り出して保存する */
    private fun capture() {
        lifecycleScope.launch(Dispatchers.IO) {
            // ImageReader から取り出す
            val image = imageReader?.acquireLatestImage() ?: return@launch
            val imageBytes = image.planes?.first()?.buffer
            val readBitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            readBitmap.copyPixelsFromBuffer(imageBytes)
            // 縦の場合は width と height を合わせる。しないと歪む
            val bitmap = if (!isLandscape) {
                val resizedBitmap = Bitmap.createScaledBitmap(readBitmap, image.height, image.width, true)
                readBitmap.recycle()
                resizedBitmap
            } else readBitmap
            // ギャラリーに登録する
            val contentResolver = contentResolver
            val contentValues = contentValuesOf(
                MediaStore.Images.Media.DISPLAY_NAME to "${System.currentTimeMillis()}.jpg",
                MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_PICTURES}/ArisaDroid"
            )
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@launch
            contentResolver.openOutputStream(uri).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }
            bitmap.recycle()
            image.close()
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
