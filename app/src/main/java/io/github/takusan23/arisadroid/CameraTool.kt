package io.github.takusan23.arisadroid

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object CameraTool {

    /** 前面、背面 カメラのIDを返す */
    fun getCameraId(context: Context): Pair<String, String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraId = cameraManager.cameraIdList.first { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val frontCameraId = cameraManager.cameraIdList.first { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        return backCameraId to frontCameraId
    }

}