package io.nekohasekai.sagernet.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.routing.RoutingLinkProcessors
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.ScannerScreen
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class ScannerActivity : ThemedActivity() {

    companion object {
        private const val TAG = "ScannerActivity"
        const val EXTRA_RETURN_SCAN_TEXT = "returnScanText"
        const val EXTRA_SCAN_TEXT = "scanText"
    }

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchEnabled by mutableStateOf(false)
    private var analyzingImage = AtomicBoolean(false)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val checkedNonAmneziaBarcodes = hashSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        setContent {
            NekoComposeTheme {
                ScannerScreen(
                    torchEnabled = torchEnabled,
                    onPreviewReady = { view ->
                        if (previewView !== view) {
                            previewView = view
                            startCamera()
                        }
                    },
                    onClose = ::finish,
                    onImportFile = { startFilesForResult(importCodeFile, "image/*") },
                    onToggleTorch = ::toggleTorchState,
                )
            }
        }
    }

    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        runOnDefaultDispatcher {
            try {
                val codes = mutableListOf<String>()
                it.forEachTry { uri ->
                    codes += QrCodeImageDecoder.decode(this@ScannerActivity, uri)
                }

                if (intent.getBooleanExtra(EXTRA_RETURN_SCAN_TEXT, false)) {
                    onMainDispatcher {
                        val code = codes.firstOrNull()
                        if (code == null) {
                            Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                        } else {
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SCAN_TEXT, code))
                        }
                        finish()
                    }
                    return@runOnDefaultDispatcher
                }

                val singleCode = codes.singleOrNull()
                if (singleCode != null && RoutingLinkProcessors.forLink(singleCode) != null) {
                    onMainDispatcher { openRoutingImport(singleCode) }
                    return@runOnDefaultDispatcher
                }
                if (singleCode != null && SubscriptionLinkImportPolicy.isHappCryptLink(singleCode)) {
                    onMainDispatcher { showHappCryptAndFinish() }
                    return@runOnDefaultDispatcher
                }

                var happCryptFound = false
                codes.forEach { code ->
                    if (SubscriptionLinkImportPolicy.isHappCryptLink(code)) {
                        happCryptFound = true
                    } else {
                        importQrPayload(code)
                    }
                }
                onMainDispatcher {
                    if (happCryptFound) {
                        showHappCryptAndFinish()
                    } else {
                        finish()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    var finished = AtomicBoolean(false)
    var importedN = AtomicInteger(0)

    /**
     * 接收扫码结果回调
     * @param result 扫码结果
     * @return 返回true表示拦截，将不自动执行后续逻辑，为false表示不拦截，默认不拦截
     */
    fun onScanText(result: String?, multi: Boolean): Boolean {
        if (!multi && finished.get()) return true

        val text = try {
            result ?: throw Exception("QR code not found")
        } catch (e: Throwable) {
            if (!multi) {
                finished.set(true)
                finish()
            }
            Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
            return true
        }

        if (!multi) {
            finished.set(true)
        }
        if (intent.getBooleanExtra(EXTRA_RETURN_SCAN_TEXT, false)) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SCAN_TEXT, text))
            finish()
            return true
        }
        if (RoutingLinkProcessors.forLink(text) != null) {
            openRoutingImport(text)
            return true
        }
        if (SubscriptionLinkImportPolicy.isHappCryptLink(text)) {
            happCryptUnsupportedDialog().apply {
                if (!multi) setOnDismissListener { finish() }
            }.show()
            return true
        }
        runOnDefaultDispatcher {
            try {
                importQrPayload(text)
            } catch (e: AmneziaApiKeyUnsupportedException) {
                onMainDispatcher {
                    Toast.makeText(app, R.string.amnezia_api_key_unsupported, Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                Logs.w(e)
                onMainDispatcher {
                    var text = getString(R.string.action_import_err)
                    text += "\n" + e.readableMessage
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (!multi) {
                    onMainDispatcher {
                        finish()
                    }
                }
            }
        }
        return true
    }

    private suspend fun importQrPayload(text: String) {
        when (val importResult = QrCodeImportParser.parse(text)) {
            is QrCodeImportResult.Profiles -> {
                val currentGroupId = DataStore.selectedGroupForImport()
                if (DataStore.selectedGroup != currentGroupId) {
                    DataStore.selectedGroup = currentGroupId
                }
                DataStore.editingGroup = currentGroupId

                ProfileManager.createProfiles(currentGroupId, importResult.profiles)
                importedN.addAndGet(importResult.profiles.size)
            }
            is QrCodeImportResult.Subscription -> onMainDispatcher {
                startActivity(Intent(this@ScannerActivity, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = SubscriptionLinkImportPolicy.toImportLink(importResult.candidate).toUri()
                })
            }
            QrCodeImportResult.Empty -> onMainDispatcher {
                Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openRoutingImport(link: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = link.toUri()
        })
        finish()
    }

    private fun showHappCryptAndFinish() {
        happCryptUnsupportedDialog().apply {
            setOnDismissListener { finish() }
        }.show()
    }

    /**
     * 启动相机预览
     */
    fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraX()
        } else {
            Log.d(TAG, "checkPermissionResult != PERMISSION_GRANTED")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    private fun startCameraX() {
        val previewView = previewView ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    if (finished.get() || !analyzingImage.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    if (imageProxy.image == null) {
                        analyzingImage.set(false)
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    try {
                        val codes = decodeQrCodesFromImageProxy(imageProxy)
                        if (codes.isNotEmpty()) {
                            runOnUiThread {
                                processLiveQrCodes(codes)
                            }
                        }
                    } finally {
                        analyzingImage.set(false)
                        imageProxy.close()
                    }
                }
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                torchEnabled = false
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start CameraX scanner", e)
                Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processLiveQrCodes(codes: List<String>) {
        if (finished.get()) return
        codes.forEach { code ->
            if (finished.get()) return@forEach
            if (code.isNotEmpty()) {
                if (checkedNonAmneziaBarcodes.add(code)) {
                    Log.d(TAG, "ZXing accepted QR payload")
                    onScanText(code, false)
                }
            }
        }
    }

    private fun decodeQrCodesFromImageProxy(imageProxy: ImageProxy): List<String> {
        val image = imageProxy.image ?: return emptyList()
        val yPlane = image.planes[0]
        val compactY = compactYPlane(
            yPlane.buffer,
            yPlane.rowStride,
            yPlane.pixelStride,
            image.width,
            image.height,
        )
        val (sourceData, sourceWidth, sourceHeight) = rotateYPlane(
            compactY,
            image.width,
            image.height,
            imageProxy.imageInfo.rotationDegrees,
        )
        return decodeQrCodesFromYPlane(sourceData, sourceWidth, sourceHeight)
    }

    private fun compactYPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val source = buffer.duplicate()
        source.rewind()
        if (rowStride == width && pixelStride == 1) {
            return ByteArray(width * height).also { source.get(it) }
        }

        val data = ByteArray(width * height)
        var outputOffset = 0
        for (row in 0 until height) {
            val rowOffset = row * rowStride
            for (column in 0 until width) {
                data[outputOffset++] = source.get(rowOffset + column * pixelStride)
            }
        }
        return data
    }

    private fun rotateYPlane(
        data: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): Triple<ByteArray, Int, Int> {
        return when (rotationDegrees) {
            90 -> {
                val rotated = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[x * height + height - y - 1] = data[x + y * width]
                    }
                }
                Triple(rotated, height, width)
            }
            180 -> {
                val rotated = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[(height - y - 1) * width + width - x - 1] = data[x + y * width]
                    }
                }
                Triple(rotated, width, height)
            }
            270 -> {
                val rotated = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[(width - x - 1) * height + y] = data[x + y * width]
                    }
                }
                Triple(rotated, height, width)
            }
            else -> Triple(data, width, height)
        }
    }

    private fun decodeQrCodesFromYPlane(data: ByteArray, width: Int, height: Int): List<String> {
        val results = linkedSetOf<String>()
        scanRects(width, height).forEach { rect ->
            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                rect.left,
                rect.top,
                rect.width,
                rect.height,
                false,
            )
            decodeLiveQrSource(source).forEach { result ->
                result.text?.let { results.add(it) }
            }
        }
        return results.toList()
    }

    private data class ScanRect(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private fun scanRects(width: Int, height: Int): List<ScanRect> {
        val rects = mutableListOf<ScanRect>()
        fun add(left: Int, top: Int, rectWidth: Int, rectHeight: Int) {
            val safeLeft = left.coerceIn(0, width - 1)
            val safeTop = top.coerceIn(0, height - 1)
            val safeWidth = rectWidth.coerceAtMost(width - safeLeft)
            val safeHeight = rectHeight.coerceAtMost(height - safeTop)
            if (safeWidth > 0 && safeHeight > 0) {
                rects.add(ScanRect(safeLeft, safeTop, safeWidth, safeHeight))
            }
        }

        val square = minOf(width, height)
        listOf(94, 82, 70).forEach { percent ->
            val side = square * percent / 100
            add((width - side) / 2, (height - side) / 2, side, side)
        }

        add(0, 0, width, height)

        return rects
    }

    private fun decodeLiveQrSource(source: LuminanceSource): List<Result> {
        val hints = qrDecodeHints()
        runCatching {
            return listOf(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints))
        }
        runCatching {
            return listOf(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(InvertedLuminanceSource(source))), hints))
        }
        return emptyList()
    }

    private fun qrDecodeHints(): Map<DecodeHintType, Any> {
        return mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to EnumSet.of(BarcodeFormat.QR_CODE),
        )
    }

    /**
     * 释放相机
     */
    private fun releaseCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        previewView = null
        torchEnabled = false
        analyzerExecutor.shutdown()
    }

    /**
     * 切换闪光灯状态（开启/关闭）
     */
    private fun toggleTorchState() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) return

        val isTorch = activeCamera.cameraInfo.torchState.value == androidx.camera.core.TorchState.ON
        val torchResult = activeCamera.cameraControl.enableTorch(!isTorch)
        torchResult.addListener({
            runCatching { torchResult.get() }
                .onSuccess { torchEnabled = !isTorch }
                .onFailure { error -> Log.w(TAG, "Unable to change torch state", error) }
        }, ContextCompat.getMainExecutor(this))
    }

    val CAMERA_PERMISSION_REQUEST_CODE = 0X86

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    /**
     * 请求Camera权限回调结果
     * @param permissions
     * @param grantResults
     */
    fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        releaseCamera()
        super.onDestroy()
        if (importedN.get() > 0) {
            var text = getString(R.string.action_import_msg)
            text += "\n" + importedN.get() + " profile(s)"
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }
}
