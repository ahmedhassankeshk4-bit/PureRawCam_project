/*
 * PureRawCam - minimal Camera2 RAW (DNG) capture, no AI / no beautify.
 * Gradle: minSdk 31, targetSdk 35, NO extra dependencies (platform APIs only).
 * Saves to DCIM/PureRaw via MediaStore (no storage permission needed).
 */
package com.example.purerawcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlin.math.abs

private enum class State { PREVIEW, WAITING_FOCUS, CAPTURING }

/** One selectable lens: a camera id, optionally a hidden physical id behind a logical camera. */
private data class CamOption(val id: String, val physicalId: String?, val label: String)

/** TextureView that keeps the preview aspect ratio (no stretching). */
class AutoFitTextureView(context: Context) : TextureView(context) {
    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(w: Int, h: Int) {
        ratioW = w
        ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        when {
            ratioW == 0 || ratioH == 0 -> setMeasuredDimension(w, h)
            w * ratioH < h * ratioW -> setMeasuredDimension(w, w * ratioH / ratioW)
            else -> setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}

class MainActivity : Activity() {

    private lateinit var manager: CameraManager
    private lateinit var textureView: AutoFitTextureView
    private lateinit var statusTv: TextView
    private lateinit var lensBtn: Button
    private lateinit var shutterBtn: Button
    private lateinit var orientationListener: OrientationEventListener

    private val bgThread = HandlerThread("camera-bg").apply { start() }
    private val bg = Handler(bgThread.looper)
    private val bgExecutor = Executor { bg.post(it) }

    private var options = emptyList<CamOption>()
    private var optionIndex = 0
    private val current: CamOption? get() = options.getOrNull(optionIndex)

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var chars: CameraCharacteristics? = null   // characteristics of the lens actually used
    private var previewSize = Size(1280, 960)
    private var hasAf = false
    private var opening = false
    private var deviceOrientation = 0
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    @Volatile private var active = false
    @Volatile private var state = State.PREVIEW

    // ------------------------------------------------------------------ lifecycle / UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(CameraManager::class.java)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        textureView = AutoFitTextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(match, match, Gravity.CENTER))

        statusTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(statusTv, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        lensBtn = Button(this).apply { text = "Lens"; setOnClickListener { switchLens() } }
        shutterBtn = Button(this).apply {
            text = "● RAW"
            isEnabled = false
            setOnClickListener { bg.post { startCapture() } }
        }
        val infoBtn = Button(this).apply { text = "Info"; setOnClickListener { showInfo() } }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            for (b in listOf(lensBtn, shutterBtn, infoBtn)) addView(b, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        root.addView(bar, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = ensureStarted()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        // Activity is locked to portrait, so track the real phone orientation for the DNG rotation tag.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != OrientationEventListener.ORIENTATION_UNKNOWN) {
                    deviceOrientation = ((o + 45) / 90 * 90) % 360
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        orientationListener.enable()
        ensureStarted()
    }

    override fun onPause() {
        active = false
        orientationListener.disable()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        bgThread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) ensureStarted()
        else status("Camera permission is required")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun status(msg: String) = runOnUiThread { statusTv.text = msg }

    private fun ensureStarted() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (options.isEmpty()) options = runCatching { listOptions() }.getOrDefault(emptyList())
        if (options.isEmpty()) {
            status("No RAW-capable camera is exposed to apps. Tap Info to see what the phone allows.")
            return
        }
        openCamera()
    }

    private fun switchLens() {
        if (options.size < 2) {
            status("Only one RAW-capable camera is exposed to apps")
            return
        }
        closeCamera()
        optionIndex = (optionIndex + 1) % options.size
        openCamera()
    }

    // ------------------------------------------------------------------ camera discovery

    private fun rawSizes(c: CameraCharacteristics): Array<Size> =
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray<Size>()

    private fun hasRaw(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true && rawSizes(c).isNotEmpty()

    private fun facing(c: CameraCharacteristics) =
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) "Front" else "Back"

    private fun focal(c: CameraCharacteristics): String =
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?.let { String.format(Locale.US, "%.1fmm", it) } ?: ""

    /** All RAW-capable lenses, including hidden physical cameras behind logical multi-cameras. */
    private fun listOptions(): List<CamOption> {
        val ids = manager.cameraIdList
        val out = mutableListOf<CamOption>()
        for (id in ids) {
            val c = manager.getCameraCharacteristics(id)
            if (hasRaw(c)) out += CamOption(id, null, "${facing(c)} #$id ${focal(c)}".trim())
            for (p in c.physicalCameraIds) {
                if (p in ids) continue
                val pc = runCatching { manager.getCameraCharacteristics(p) }.getOrNull() ?: continue
                if (hasRaw(pc)) out += CamOption(id, p, "${facing(c)} #$id/$p ${focal(pc)}".trim())
            }
        }
        return out
    }

    // ------------------------------------------------------------------ open / session

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!active || opening || camera != null || !textureView.isAvailable) return
        val opt = current ?: return
        try {
            val c = manager.getCameraCharacteristics(opt.physicalId ?: opt.id)
            val raw = rawSizes(c).maxByOrNull { it.width.toLong() * it.height } ?: return
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val preview = choosePreview(map.getOutputSizes(SurfaceTexture::class.java), raw)
            val swap = (c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 == 90

            chars = c
            previewSize = preview
            hasAf = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            rawReader = ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 2).also {
                it.setOnImageAvailableListener(imageListener, bg)
            }
            runOnUiThread {
                textureView.setAspectRatio(
                    if (swap) preview.height else preview.width,
                    if (swap) preview.width else preview.height
                )
                lensBtn.text = opt.label
            }
            status("${opt.label}\nRAW ${raw.width}x${raw.height} (${raw.width.toLong() * raw.height / 1_000_000} MP)")
            opening = true
            manager.openCamera(opt.id, stateCallback, bg)
        } catch (e: Exception) {
            opening = false
            status("Open failed: ${e.message}")
        }
    }

    private fun choosePreview(sizes: Array<Size>, raw: Size): Size {
        val ratio = raw.width.toFloat() / raw.height
        val ok = sizes.filter { it.width <= 1920 && it.height <= 1440 }
        return ok.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }
            .maxByOrNull { it.width * it.height }
            ?: ok.maxByOrNull { it.width * it.height }
            ?: sizes.first()
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            opening = false
            if (!active) {
                device.close()
                return
            }
            camera = device
            createSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            opening = false
            device.close()
            camera = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            opening = false
            device.close()
            camera = null
            status("Camera error $error")
        }
    }

    private fun createSession(device: CameraDevice) {
        try {
            val opt = current ?: return
            val reader = rawReader ?: return
            val st = textureView.surfaceTexture ?: return
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(st)
            previewSurface = surface

            val previewCfg = OutputConfiguration(surface)
            val rawCfg = OutputConfiguration(reader.surface)
            opt.physicalId?.let {
                previewCfg.setPhysicalCameraId(it)
                rawCfg.setPhysicalCameraId(it)
            }

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(surface)
                        applyPureSettings(b)
                        previewBuilder = b
                        s.setRepeatingRequest(b.build(), previewCallback, bg)
                        runOnUiThread { shutterBtn.isEnabled = true }
                    } catch (e: Exception) {
                        status("Preview failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    status("This lens can't stream preview + RAW together. Tap Lens to try another.")
                }
            }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(previewCfg, rawCfg), bgExecutor, callback
                )
            )
        } catch (e: Exception) {
            status("Session failed: ${e.message}")
        }
    }

    private fun closeCamera() {
        bg.removeCallbacks(focusTimeout)
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        previewBuilder = null
        pendingImage = null
        pendingResult = null
        state = State.PREVIEW
        opening = false
        shutterBtn.isEnabled = false
    }

    // ------------------------------------------------------------------ request settings (the "pure" part)

    /**
     * Everything that smooths / sharpens / beautifies is forced OFF when the phone lists OFF as supported.
     * 3A (AE / AF / AWB) stays automatic. Nothing here enables HDR, night mode, face retouch or any extension.
     */
    private fun applyPureSettings(b: CaptureRequest.Builder) {
        val c = chars ?: return

        fun setOff(key: CaptureRequest.Key<Int>, avail: CameraCharacteristics.Key<IntArray>, off: Int) {
            if (c.get(avail)?.contains(off) == true) b.set(key, off)
        }

        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (hasAf) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CameraMetadata.CONTROL_AF_MODE_OFF
        )
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)

        setOff(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF
        )
        setOff(
            CaptureRequest.EDGE_MODE,
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
            CameraMetadata.EDGE_MODE_OFF
        )
        setOff(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
        )
        setOff(
            CaptureRequest.CONTROL_EFFECT_MODE,
            CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS,
            CameraMetadata.CONTROL_EFFECT_MODE_OFF
        )

        // Lets the DNG carry the lens-shading (vignetting) map instead of baking a correction in.
        if (c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true
        ) {
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
        // Optical stabilisation is physical (a moving lens), not software processing.
        if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        ) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    // ------------------------------------------------------------------ capture flow (runs on bg thread)

    private val focusTimeout = Runnable { if (state == State.WAITING_FOCUS) captureRaw() }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (state != State.WAITING_FOCUS) return
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (af == null ||
                af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) captureRaw()
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            pendingResult = result
            trySave()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            finishShot("Capture failed (reason ${f.reason})")
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val img = reader.acquireNextImage() ?: return@OnImageAvailableListener
        if (state != State.CAPTURING) {
            img.close()
            return@OnImageAvailableListener
        }
        pendingImage?.close()
        pendingImage = img
        trySave()
    }

    private fun startCapture() {
        val s = session ?: return
        val b = previewBuilder ?: return
        if (state != State.PREVIEW) return
        runOnUiThread { shutterBtn.isEnabled = false }
        if (!hasAf) {
            captureRaw()
            return
        }
        try {
            state = State.WAITING_FOCUS
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, bg)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            bg.postDelayed(focusTimeout, 2000)
        } catch (e: Exception) {
            finishShot("Focus failed: ${e.message}")
        }
    }

    private fun captureRaw() {
        bg.removeCallbacks(focusTimeout)
        val dev = camera ?: return finishShot("Camera not ready")
        val s = session ?: return finishShot("Camera not ready")
        val reader = rawReader ?: return finishShot("Camera not ready")
        state = State.CAPTURING
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(reader.surface)
            applyPureSettings(b)
            s.capture(b.build(), stillCallback, bg)
        } catch (e: Exception) {
            finishShot("Capture failed: ${e.message}")
        }
    }

    /** Called once both the RAW image and its capture metadata have arrived. */
    private fun trySave() {
        val img = pendingImage ?: return
        val res = pendingResult ?: return
        pendingImage = null
        pendingResult = null
        val c = chars
        val msg = try {
            if (c == null) error("camera closed")
            "Saved ✓ " + saveDng(img, c, res, current?.physicalId)
        } catch (e: Exception) {
            "Save failed: ${e.message}"
        }
        runCatching { img.close() }
        finishShot(msg)
    }

    private fun finishShot(msg: String) {
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        try {
            val s = session
            val b = previewBuilder
            if (s != null && b != null) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, bg)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                s.setRepeatingRequest(b.build(), previewCallback, bg)
            }
        } catch (e: Exception) {
            // session was already closed
        }
        state = State.PREVIEW
        runOnUiThread {
            statusTv.text = msg
            shutterBtn.isEnabled = camera != null
        }
    }

    // ------------------------------------------------------------------ DNG writing

    private fun makeDng(c: CameraCharacteristics, res: TotalCaptureResult, physId: String?): DngCreator {
        // For a physical lens prefer its own result; fall back to the logical result if keys are missing.
        val candidates: List<CaptureResult> = listOfNotNull(physId?.let { res.physicalCameraResults[it] }, res)
        var lastErr: Exception? = null
        for (meta in candidates) {
            try {
                return DngCreator(c, meta)
            } catch (e: IllegalArgumentException) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("No metadata for DNG")
    }

    private fun exifOrientation(c: CameraCharacteristics): Int {
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val deg = if (front) (sensor + deviceOrientation) % 360 else (sensor - deviceOrientation + 360) % 360
        return when (deg) {
            90 -> 6    // EXIF ROTATE_90
            180 -> 3   // EXIF ROTATE_180
            270 -> 8   // EXIF ROTATE_270
            else -> 1  // EXIF NORMAL
        }
    }

    private fun saveDng(image: Image, c: CameraCharacteristics, res: TotalCaptureResult, physId: String?): String {
        val name = "RAW_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".dng"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/PureRaw")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            val dng = makeDng(c, res, physId)
            try {
                dng.setOrientation(exifOrientation(c))
                val out = contentResolver.openOutputStream(uri) ?: error("Cannot open output stream")
                out.use { dng.writeImage(it, image) }
            } finally {
                dng.close()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return "DCIM/PureRaw/$name"
    }

    // ------------------------------------------------------------------ Info dialog (what does the phone really allow?)

    private fun showInfo() {
        val sb = StringBuilder("${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE}\n\n")
        val ids = manager.cameraIdList
        for (id in ids) {
            runCatching {
                val c = manager.getCameraCharacteristics(id)
                describe(sb, "Camera $id", c)
                for (p in c.physicalCameraIds) {
                    val note = if (p in ids) "(also listed)" else "(hidden)"
                    runCatching { describe(sb, "  > physical $p $note", manager.getCameraCharacteristics(p)) }
                }
            }.onFailure { sb.append("Camera $id: ${it.message}\n\n") }
        }
        sb.append("Active: ${current?.label ?: "-"}")
        AlertDialog.Builder(this)
            .setTitle("Camera info")
            .setMessage(sb)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun describe(sb: StringBuilder, title: String, c: CameraCharacteristics) {
        fun offOk(key: CameraCharacteristics.Key<IntArray>, off: Int) =
            if (c.get(key)?.contains(off) == true) "OK" else "NO"

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val raw = rawSizes(c).maxByOrNull { it.width.toLong() * it.height }
        val maxRes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        val nr = offOk(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF
        )
        val edge = offOk(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF)
        val ca = offOk(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
        )

        sb.append("$title: ${facing(c)} ${focal(c)}\n")
        sb.append("  RAW cap=${CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps}  max RAW=${raw ?: "-"}\n")
        sb.append("  max-res RAW=${maxRes ?: "-"}\n")
        sb.append("  OFF supported -> NR:$nr  Edge:$edge  CA:$ca\n\n")
    }
}
