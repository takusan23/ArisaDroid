package io.github.takusan23.arisadroid

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * カメラ映像をレンダリングする
 * フロント、バックではなく、メイン、サブにしている。後で切り替え機能を作るため
 *
 * @param rotation 映像を回転する
 * @param mainSurfaceTexture メイン映像
 * @param subSurfaceTexture サブ映像。ワイプカメラ
 */
class CameraGLRenderer(
    private val rotation: Float,
    private val mainSurfaceTexture: () -> SurfaceTexture,
    private val subSurfaceTexture: () -> SurfaceTexture,
) {

    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private val mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * FLOAT_SIZE_BYTES).run {
        order(ByteOrder.nativeOrder())
        asFloatBuffer().apply {
            put(mTriangleVerticesData)
            position(0)
        }
    }

    // ハンドルたち
    private var mProgram = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    // テクスチャID
    // SurfaceTexture に渡す
    private var mainCameraTextureId = 0
    private var subCameraTextureId = 0

    // テクスチャのハンドル
    private var uMainCameraTextureHandle = 0
    private var uSubCameraTextureHandle = 0
    private var uDrawMainCameraHandle = 0

    /** 描画する */
    fun onDrawFrame() {
        prepareDraw()
        drawMainCamera(mainSurfaceTexture())
        drawSubCamera(subSurfaceTexture())
        GLES20.glFinish()
    }

    /**
     * シェーダーの用意をする。
     * テクスチャIDを返すので、SurfaceTexture のコンストラクタ入れてね。
     *
     * @return メイン映像、サブ映像のテクスチャID。SurfaceTexture のコンストラクタ に入れる。
     */
    fun setupProgram(): Pair<Int, Int> {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        uMainCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "uMainCameraTexture")
        checkGlError("glGetUniformLocation uMainCameraTextureHandle")
        if (uMainCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMainCameraTextureHandle")
        }
        uSubCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "uSubCameraTexture")
        checkGlError("glGetUniformLocation uSubCameraTexture")
        if (uSubCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSubCameraTexture")
        }
        uDrawMainCameraHandle = GLES20.glGetUniformLocation(mProgram, "uDrawMainCamera")
        checkGlError("glGetUniformLocation uDrawMainCameraHandle")
        if (uDrawMainCameraHandle == -1) {
            throw RuntimeException("Could not get attrib location for uDrawMainCameraHandle")
        }

        // カメラ2つなので、2つ分のテクスチャを作成
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        // メイン映像
        mainCameraTextureId = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mainCameraTextureId)
        checkGlError("glBindTexture mainCameraTextureId")

        // 縮小拡大時の補間設定
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameteri mainCameraTexture")

        // サブ映像
        subCameraTextureId = textures[1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, subCameraTextureId)
        checkGlError("glBindTexture subCameraTextureId")

        // 縮小拡大時の補間設定
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameteri subCameraTexture")

        // アルファブレンドを有効
        // これにより、透明なテクスチャがちゃんと透明に描画される
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError("glEnable BLEND")

        return subCameraTextureId to mainCameraTextureId
    }

    /** 描画前に呼び出す */
    private fun prepareDraw() {
        // glError 1282 の原因とかになる
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        // Snapdragon だと glClear が無いと映像が乱れる
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
    }

    /** メイン映像の [SurfaceTexture] を描画する */
    private fun drawMainCamera(surfaceTexture: SurfaceTexture) {
        // テクスチャ更新。呼ばないと真っ黒
        surfaceTexture.updateTexImage()
        checkGlError("drawMainCamera start")
        surfaceTexture.getTransformMatrix(mSTMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, subCameraTextureId)
        // メイン映像のテクスチャIDは GLES20.GL_TEXTURE0 なので 0
        GLES20.glUniform1i(uMainCameraTextureHandle, 0)
        // サブ映像のテクスチャIDは GLES20.GL_TEXTURE1 なので 1
        GLES20.glUniform1i(uSubCameraTextureHandle, 1)
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        // ----
        // メイン映像を描画するフラグを立てる
        // ----
        GLES20.glUniform1i(uDrawMainCameraHandle, 1)
        // Matrix.XXX のユーティリティー関数で行列の操作をする場合、適用させる順番に注意する必要があります
        Matrix.setIdentityM(mMVPMatrix, 0)
        // 画面回転している場合は回転する
        Matrix.rotateM(mMVPMatrix, 0, rotation, 0f, 0f, 1f)

        // 描画する
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays drawMainCamera")
    }

    /** サブ映像の [SurfaceTexture] を描画する */
    private fun drawSubCamera(surfaceTexture: SurfaceTexture) {
        // テクスチャ更新。呼ばないと真っ黒
        surfaceTexture.updateTexImage()
        checkGlError("drawSubCamera start")
        surfaceTexture.getTransformMatrix(mSTMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mainCameraTextureId)
        // メイン映像のテクスチャIDは GLES20.GL_TEXTURE0 なので 0
        GLES20.glUniform1i(uMainCameraTextureHandle, 0)
        // サブ映像のテクスチャIDは GLES20.GL_TEXTURE1 なので 1
        GLES20.glUniform1i(uSubCameraTextureHandle, 1)
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        // ----
        // メイン映像を描画するフラグを下ろしてサブ映像を描画する
        // ----
        GLES20.glUniform1i(uDrawMainCameraHandle, 0)
        // Matrix.XXX のユーティリティー関数で行列の操作をする場合、適用させる順番に注意する必要があります
        Matrix.setIdentityM(mMVPMatrix, 0)
        // 右上に移動させる
        Matrix.translateM(mMVPMatrix, 0, 1f - 0.3f, 1f - 0.3f, 1f)
        // 半分ぐらいにする
        Matrix.scaleM(mMVPMatrix, 0, 0.3f, 0.3f, 1f)
        // 画面回転している場合は回転する
        Matrix.rotateM(mMVPMatrix, 0, rotation, 0f, 0f, 1f)

        // 描画する
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays drawSubCamera")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            return 0
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }

    companion object {
        private val mTriangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )

        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        /** バーテックスシェーダー。座標などを決める */
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        /** フラグメントシェーダー。実際の色を返す */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uMainCameraTexture;        
            uniform samplerExternalOES uSubCameraTexture;        
            
            // メイン映像を描画する場合は 1
            uniform int uDrawMainCamera;
        
            void main() {
                vec4 mainCameraTexture = texture2D(uMainCameraTexture, vTextureCoord);
                vec4 subCameraTexture = texture2D(uSubCameraTexture, vTextureCoord);
                
                if (bool(uDrawMainCamera)) {
                    gl_FragColor = mainCameraTexture;                
                } else {
                    gl_FragColor = subCameraTexture;
                }
            }
        """
    }

}