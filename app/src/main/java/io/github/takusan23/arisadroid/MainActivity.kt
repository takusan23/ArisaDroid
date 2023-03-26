package io.github.takusan23.arisadroid

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
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
            // onResume で代替
            // setup()
        }
    }

    /** Surface を待つ */
    private val availableSurfaceFlow = MutableStateFlow<Surface?>(null)

    /** 生成した [GLSurface] */
    private val glSurfaceList = arrayListOf<GLSurface>()

    /** 利用中の [CameraControl] */
    private val cameraControlList = arrayListOf<CameraControl>()

    /** 生成した [SurfaceTexture] */
    private val previewSurfaceTexture = arrayListOf<SurfaceTexture>()

    /** onFrameAvailable が呼ばれたら +1 していく */
    private var unUsedFrameCount = 0L

    /** updateTexUpdate を呼んだら +1 していく */
    private var usedFrameCount = 0L

    /** カメラ用スレッド */
    private var cameraJob: Job? = null

    /**
     * 撮影モード
     *
     * 静止画撮影なら[imageReader]、動画撮影なら[mediaRecorder]が使われます
     */
    private var currentCaptureMode = CameraCaptureMode.VIDEO

    /** 静止画撮影  */
    private var imageReader: ImageReader? = null

    /** 録画機能 */
    private var mediaRecorder: MediaRecorder? = null

    /** 録画中か */
    private var isRecording = false

    /** 録画中ファイル */
    private var saveVideoFile: File? = null

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
            val zoomValue = remember { mutableStateOf(1f) }
            val zoomRange = remember { mutableStateOf(0f..1f) }
            SideEffect {
                // 非 Compose なコードので若干違和感
                zoomRange.value = cameraControlList.firstOrNull()?.zoomRange ?: 0f..1f
            }

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
                    factory = {
                        surfaceView.apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    availableSurfaceFlow.value = holder.surface
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    // do nothing
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    // Surface破棄時はEGLも破棄
                                    lifecycleScope.launch { cameraDestroy() }
                                }
                            })
                        }
                    }
                )
                Column(
                    modifier = Modifier
                        .padding(bottom = 50.dp)
                        .align(Alignment.BottomCenter),
                ) {
                    Slider(
                        value = zoomValue.value,
                        valueRange = zoomRange.value,
                        onValueChange = {
                            zoomValue.value = it
                            // 前面カメラ は最初
                            cameraControlList.first().zoom(it)
                        }
                    )
                    Button(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        onClick = { capture() }
                    ) { Text(text = "撮影 録画 する") }
                }
            }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // 更新を通知するため、値を更新する
        unUsedFrameCount++
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
        lifecycleScope.launch(Dispatchers.IO) {
            cameraDestroy()
        }
    }

    /** リソース開放。サスペンド関数なので終わるまで一時停止する */
    private suspend fun cameraDestroy() {
        // キャンセル待ちをすることでGLのループを抜けるのを待つ（多分描画中に終了すると落ちる）
        cameraJob?.cancelAndJoin()
        previewSurfaceTexture.forEach {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
        previewSurfaceTexture.clear()
        imageReader?.close()
        glSurfaceList.forEach { it.release() }
        glSurfaceList.clear()
        cameraControlList.forEach { it.destroy() }
        cameraControlList.clear()
        if (isRecording) {
            mediaRecorder?.stop()
        }
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun setup() {
        cameraJob = lifecycleScope.launch(Dispatchers.IO) {
            // SurfaceView を待つ
            val previewSurface = availableSurfaceFlow.filterNotNull().first()
            println("previewSurface")
            // 撮影モードに合わせた Surface を作る（静止画撮影、動画撮影）
            val captureSurface = if (currentCaptureMode == CameraCaptureMode.PICTURE) {
                // 静止画撮影で利用する ImageReader
                // Surface の入力から画像を生成できる
                val imageReader = ImageReader.newInstance(
                    if (isLandscape) CAMERA_RESOLTION_WIDTH else CAMERA_RESOLTION_HEIGHT,
                    if (isLandscape) CAMERA_RESOLTION_HEIGHT else CAMERA_RESOLTION_WIDTH,
                    PixelFormat.RGBA_8888, // JPEG は OpenGL 使ったせいなのか利用できない
                    2
                )
                this@MainActivity.imageReader = imageReader
                imageReader.surface
            } else {
                // メソッド呼び出しには順番があります
                val mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@MainActivity) else MediaRecorder()).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(2)
                    setVideoEncodingBitRate(1_000_000)
                    setVideoFrameRate(30)
                    if (isLandscape) {
                        setVideoSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                    } else {
                        setVideoSize(CAMERA_RESOLTION_HEIGHT, CAMERA_RESOLTION_WIDTH)
                    }
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    saveVideoFile = File(getExternalFilesDir(null), "${System.currentTimeMillis()}.mp4")
                    setOutputFile(saveVideoFile!!)
                    prepare()
                }
                this@MainActivity.mediaRecorder = mediaRecorder
                mediaRecorder.surface
            }

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
                surface = captureSurface,
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
            cameraControlList += CameraControl(this@MainActivity, backCameraId, Surface(mainSurfaceTexture[0]), Surface(mainSurfaceTexture[1]))
            cameraControlList += CameraControl(this@MainActivity, frontCameraId, Surface(subSurfaceTexture[0]), Surface(subSurfaceTexture[1]))
            cameraControlList.forEach { it.openCamera() }
            // プレビューする
            cameraControlList.forEach { it.startCamera() }

            // OpenGL のレンダリングを行う
            // isActive でこの cameraJob が終了されるまでループし続ける
            // ここで行う理由ですが、makeCurrent したスレッドでないと glDrawArray できない？ + onFrameAvailable が UIスレッド なので重たいことはできないためです。
            // ただ、レンダリングするタイミングは onFrameAvailable が更新されたタイミングなので、
            // while ループを回して 新しいフレームが来ているか確認しています。
            while (isActive) {
                // OpenGL の描画よりも onFrameAvailable の更新のほうが早い？ため、更新が追いついてしまう
                // そのため、消費したフレームとまだ消費していないフレームを比較するようにした
                // https://stackoverflow.com/questions/14185661
                if (unUsedFrameCount != usedFrameCount && isActive) {
                    glSurfaceList.forEach {
                        it.makeCurrent() // 多分いる
                        it.drawFrame()
                        it.swapBuffers()
                    }
                    usedFrameCount += 2 // メイン映像とサブ映像で2つ
                }
            }
        }
    }

    /** 撮影、録画ボタンを押したとき */
    private fun capture() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (currentCaptureMode == CameraCaptureMode.VIDEO) {
                // 録画モード
                if (!isRecording) {
                    mediaRecorder?.start()
                } else {
                    // 多分 MediaRecorder を作り直さないといけない
                    cameraDestroy()
                    // 動画フォルダ に保存する
                    val contentResolver = contentResolver
                    val contentValues = contentValuesOf(
                        MediaStore.Video.Media.DISPLAY_NAME to saveVideoFile?.name,
                        MediaStore.Video.Media.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/ArisaDroid"
                    )
                    contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)?.also { uri ->
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            saveVideoFile?.inputStream()?.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    setup()
                }
                isRecording = !isRecording
            } else {
                // 静止画モード
                // ImageReader から取り出す
                val image = imageReader?.acquireLatestImage() ?: return@launch
                val width = image.width
                val height = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                // なぜか ImageReader のサイズに加えて、何故か Padding が入っていることを考慮する必要がある
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                // Bitmap 作成
                val readBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                readBitmap.copyPixelsFromBuffer(buffer)
                // 余分な Padding を消す
                val originWidth = if (isLandscape) CAMERA_RESOLTION_WIDTH else CAMERA_RESOLTION_HEIGHT
                val originHeight = if (isLandscape) CAMERA_RESOLTION_HEIGHT else CAMERA_RESOLTION_WIDTH
                val editBitmap = Bitmap.createBitmap(readBitmap, 0, 0, originWidth, originHeight)
                readBitmap.recycle()
                // ギャラリーに登録する
                val contentResolver = contentResolver
                val contentValues = contentValuesOf(
                    MediaStore.Images.Media.DISPLAY_NAME to "${System.currentTimeMillis()}.jpg",
                    MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_PICTURES}/ArisaDroid"
                )
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@launch
                contentResolver.openOutputStream(uri).use { outputStream ->
                    editBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                }
                editBitmap.recycle()
                image.close()
            }
        }
    }

    /** 撮影モード */
    private enum class CameraCaptureMode {
        PICTURE,
        VIDEO,
    }

    companion object {

        /** 720P 解像度 幅 */
        private const val CAMERA_RESOLTION_WIDTH = 1280

        /** 720P 解像度 高さ */
        private const val CAMERA_RESOLTION_HEIGHT = 720

    }

}