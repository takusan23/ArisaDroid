package io.github.takusan23.arisadroid

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * カメラを開けたり閉じたりする処理を隠蔽するクラス
 *
 * @param context [Context]
 * @param cameraId カメラID、前面 or 背面
 * @param previewSurface プレビューSurface
 * @param captureSurface 撮影、録画 用Surface
 */
class CameraControl(
    context: Context,
    private val cameraId: String,
    private val previewSurface: Surface,
    private val captureSurface: Surface
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null

    private var captureRequest: CaptureRequest.Builder? = null
    private var currentCaptureSession: CameraCaptureSession? = null
    private val outputList = buildList {
        add(OutputConfiguration(previewSurface))
        add(OutputConfiguration(captureSurface))
    }

    /** ズーム出来る値の範囲を返す */
    val zoomRange: ClosedFloatingPointRange<Float>
        get() = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
            // Pixel 6 Pro の場合は 0.6704426..20.0 のような値になる
            it.lower..it.upper
        } ?: 0f..0f

    /** カメラを開く */
    suspend fun openCamera() {
        cameraDevice = waitOpenCamera()
    }

    /** カメラを開始する */
    fun startCamera() {
        val cameraDevice = cameraDevice ?: return
        if (captureRequest == null) {
            captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(previewSurface)
                addTarget(captureSurface)
            }
        }
        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                currentCaptureSession = captureSession
                captureSession.setRepeatingRequest(captureRequest!!.build(), null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                // do nothing
            }
        }).apply { cameraDevice.createCaptureSession(this) }
    }

    /**
     * ズームする
     * [startCamera]を呼び出した後のみ利用可能
     */
    fun zoom(zoom: Float = 1f) {
        val captureRequest = captureRequest ?: return
        val currentCaptureSession = currentCaptureSession ?: return

        captureRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        currentCaptureSession.setRepeatingRequest(captureRequest.build(), null, null)
    }

    /** 終了時に呼び出す */
    fun destroy() {
        cameraDevice?.close()
    }

    /** [cameraId]のカメラを開く */
    @SuppressLint("MissingPermission")
    private suspend fun waitOpenCamera() = suspendCoroutine {
        cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                it.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                // do nothing
            }

            override fun onError(camera: CameraDevice, error: Int) {
                // do nothing
            }
        })
    }

}