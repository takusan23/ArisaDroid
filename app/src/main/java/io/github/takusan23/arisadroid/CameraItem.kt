package io.github.takusan23.arisadroid

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
class CameraItem(
    context: Context,
    private val cameraId: String,
    private val previewSurface: Surface,
    private val captureSurface: Surface
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null

    /** カメラを開く */
    suspend fun openCamera() {
        cameraDevice = waitOpenCamera()
    }

    /** カメラを開始する */
    fun startPreview() {
        val cameraDevice = cameraDevice ?: return
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(previewSurface)
            addTarget(captureSurface)
        }.build()
        val outputList = buildList {
            add(OutputConfiguration(previewSurface))
            add(OutputConfiguration(captureSurface))
        }
        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(captureRequest, null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                // do nothing
            }
        }).apply { cameraDevice.createCaptureSession(this) }
    }

    /** 終了時に呼び出す */
    fun destroy() {
        cameraDevice?.close()
    }

    /** [cameraId]のカメラを開く */
    @SuppressLint("MissingPermission")
    suspend private fun waitOpenCamera() = suspendCoroutine {
        cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice?) {
                it.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice?) {
                // do nothing
            }

            override fun onError(camera: CameraDevice?, error: Int) {
                // do nothing
            }
        })
    }

}