package com.ueiol.scanaianswer.ui.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.ueiol.scanaianswer.MainActivity
import com.ueiol.scanaianswer.R
import com.ueiol.scanaianswer.databinding.ActivityCaptureBinding
import com.ueiol.scanaianswer.ui.confirm.ConfirmActivity
import com.yalantis.ucrop.UCrop
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var modelGgufPath: String? = null
    // 绑定的 Camera 实例，用它对焦与控制手电筒
    private var camera: Camera? = null
    private var torchOn = false
    // 已 bound 的 Preview，供方向变化时运行时更新 targetRotation
    private var activePreview: Preview? = null
    // 屏幕方向监听器，方向变化时同步到 Preview/ImageCapture
    private var displayListener: DisplayManager.DisplayListener? = null

    // 相册选图：先从系统相册挑图，再走 uCrop 裁剪，最后 OCR 确认
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            startCrop(uri)
        }
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let {
                navigateToConfirm(it)
            } ?: run {
                Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            val error = UCrop.getError(result.data!!)
            error?.let { Timber.e(it, "Crop error") }
        }
    }

    // 确认页返回文本后，原样转发回主页面，由主页面直接对话
    private val confirmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringExtra(MainActivity.EXTRA_CONFIRMED_TEXT)
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) {
            val forward = Intent().putExtra(MainActivity.EXTRA_CONFIRMED_TEXT, text)
            setResult(RESULT_OK, forward)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelGgufPath = intent.getStringExtra(EXTRA_MODEL_CONFIG)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }
        // 点击相机预览区域对该点对焦
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
            }
            true
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 显式绑定目标旋转，避免部分设备上默认 rotation 推断与显示方向偏差
            // 产生 180° 画面倒置。竖屏锁定后该值稳定为当前 display rotation。
            val rotation = binding.viewFinder.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                // 记录 use case 供方向变化时运行时更新 targetRotation
                activePreview = preview
                registerDisplayListener()
            } catch (e: Exception) {
                Timber.e(e, "Camera binding failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 注册屏幕方向监听，方向变化时把新 rotation 同步到 Preview/ImageCapture。
     * 竖屏锁定后通常不会触发，但个别设备 display 上报仍可能变化，作为兜底保险。
     */
    private fun registerDisplayListener() {
        val display = binding.viewFinder.display ?: return
        if (displayListener != null) return

        val displayManager = getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (display.displayId != displayId) return
                updateTargetRotation(display.rotation)
            }
        }
        displayListener = listener
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun updateTargetRotation(rotation: Int) {
        activePreview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    override fun onStop() {
        super.onStop()
        displayListener?.let { listener ->
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(listener)
        }
        displayListener = null
    }

    /** 在相机预览画面给定坐标（View 像素坐标）处触发对焦 + 测光。 */
    private fun focusAt(x: Float, y: Float) {
        val camera = camera ?: return

        val factory = SurfaceOrientedMeteringPointFactory(
            binding.viewFinder.width.toFloat(),
            binding.viewFinder.height.toFloat()
        )
        val point: MeteringPoint = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // 机型无 AF 时该调用会静默忽略，故无需显式做能力判断
        camera.cameraControl.startFocusAndMetering(action)
    }

    /** 切换手电筒开关（闪光灯作为持续光源）。 */
    private fun toggleTorch() {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "该设备不支持手电筒", Toast.LENGTH_SHORT).show()
            return
        }
        val target = !torchOn
        if (target) turnOnTorch() else turnOffTorch()
    }

    private fun turnOnTorch() {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "该设备不支持手电筒", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = true
        camera.cameraControl.enableTorch(true)
        binding.btnTorch.setIconResource(R.drawable.ic_flash_on)
    }

    private fun turnOffTorch() {
        camera?.cameraControl?.enableTorch(false)
        torchOn = false
        binding.btnTorch.setIconResource(R.drawable.ic_flash_off)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 拍照时关闭手电筒（手电筒仅作拍摄照明，拍完即关）
        if (torchOn) {
            turnOffTorch()
        }

        val photoFile = File(
            cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Timber.e(exc, "Photo capture failed")
                    Toast.makeText(baseContext, "拍照失败", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    Timber.d("Photo saved: $savedUri")
                    startCrop(savedUri)
                }
            }
        )
    }

    private fun startCrop(sourceUri: Uri) {
        val destFile = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
        val options = UCrop.Options().apply {
            setCompressionQuality(90)
            setFreeStyleCropEnabled(true)
            setHideBottomControls(false)
        }

        val intent = UCrop.of(sourceUri, Uri.fromFile(destFile))
            .withOptions(options)
            .withAspectRatio(3f, 4f)
            .getIntent(this)

        cropLauncher.launch(intent)
    }

    private fun navigateToConfirm(imageUri: Uri) {
        confirmLauncher.launch(ConfirmActivity.newIntent(this, imageUri))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }


    override fun onDestroy() {
        super.onDestroy()
        // 离开页面时关闭手电筒，避免闪光灯常亮
        camera?.cameraControl?.enableTorch(false)
        cameraExecutor.shutdown()
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val EXTRA_MODEL_CONFIG = "model_config"

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()

        fun newIntent(context: Context, modelConfigPath: String?): Intent {
            return Intent(context, CaptureActivity::class.java).apply {
                putExtra(EXTRA_MODEL_CONFIG, modelConfigPath)
            }
        }
    }
}