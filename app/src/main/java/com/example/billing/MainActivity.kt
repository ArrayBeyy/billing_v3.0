package com.example.billing

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputFilter.AllCaps
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.billing.api.config.RetrofitClient
import com.example.billing.api.model.VoucherResponse
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var etVoucher: EditText
    private lateinit var btnCheck: Button
    private lateinit var prefs: SharedPreferences
    private var voucherCode: String = ""

    private val REQUEST_CODE_SCREEN_CAPTURE = 2001
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var virtualDisplay: VirtualDisplay
    private var isRecording = false
    private lateinit var outputPath: String

    private val REQUEST_CAMERA_PERMISSION = 1002
    private var hasScannedQRCode = false

    private var wasPinned = true

    private var wrongPasswordCount = 0

    companion object {
        var storedResultCode: Int = -1
        var storedDataIntent: Intent? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1003
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                1004
            )
        }

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        val versionTextView = findViewById<TextView>(R.id.textViewVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = "Versi ${pInfo.versionName} (${pInfo.versionCode})"
            versionTextView.text = version
        } catch (e: Exception) {
            versionTextView.text = "Versi tidak tersedia"
        }

        hideSystemUI()
        activateScreenPinning()

        etVoucher = findViewById(R.id.etVoucher)
        etVoucher.filters = arrayOf<InputFilter>(AllCaps())

        btnCheck = findViewById(R.id.btnCheck)
        btnCheck.setOnClickListener {
            voucherCode = etVoucher.text.toString().trim()
            if (voucherCode.isEmpty()) {
                Toast.makeText(this, "Kode voucher belum di-input.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("code_voucher", voucherCode)

            RetrofitClient.instance.readVoucher(builder.build())
                .enqueue(object : Callback<VoucherResponse> {
                    override fun onResponse(
                        call: Call<VoucherResponse>,
                        response: Response<VoucherResponse>
                    ) {
                        if (response.isSuccessful && response.body()?.message == "Voucher berhasil terbaca") {
                            unlockAndGoToWhatsApp()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Voucher tidak valid",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onFailure(call: Call<VoucherResponse>, t: Throwable) {
                        Toast.makeText(
                            this@MainActivity,
                            "Gagal koneksi API: ${t.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
        }

        val btnToggleRecording = findViewById<Button>(R.id.btnToggleRecording)
        btnToggleRecording.setOnClickListener {
            showPasswordDialog()
        }

        val btnScanQR = findViewById<Button>(R.id.btnScanQR)
        btnScanQR.setOnClickListener {
            hasScannedQRCode = false
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSION
                )
            } else {
                openFrontCamera()
            }
        }
    }

    private fun unlockAndGoToWhatsApp(code: String = voucherCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            stopLockTask()
        }
        val intent = Intent(this@MainActivity, WhatsAppActivity::class.java)
        intent.putExtra("CODE_VOUCHER", code)
        startActivity(intent)
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }

    private fun activateScreenPinning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                startLockTask()
            } catch (e: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        activateScreenPinning()

        if (!wasPinned) {
            wasPinned = true

            if (isRecording) {
                stopRecording() // otomatis stop saat user kembali
            }

            activateScreenPinning()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            storedResultCode = resultCode
            storedDataIntent = data
            prefs.edit { putBoolean("screen_permission_granted", true) }
            val serviceIntent = Intent(this, ScreenRecordService::class.java)
            serviceIntent.putExtra("resultCode", resultCode)
            serviceIntent.putExtra("data", data)
            ContextCompat.startForegroundService(this, serviceIntent)
            isRecording = true
            prefs.edit { putBoolean("recording_active", true) }
            Toast.makeText(this, "📹 Rekaman layar dimulai", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Izin ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScreenRecording() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        val movieDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val screenFolder = File(movieDir, "ScreenRecords")
        if (!screenFolder.exists()) screenFolder.mkdirs()

        val fileName = "screen_${System.currentTimeMillis()}.mp4"
        outputPath = File(screenFolder, fileName).absolutePath

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputPath)
            setVideoSize(metrics.widthPixels, metrics.heightPixels)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(5 * 1024 * 1024)
            setVideoFrameRate(30)
            prepare()
        }

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "RecordScreen",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface, null, null
        )

        mediaRecorder.start()
        isRecording = true
        prefs.edit { putBoolean("recording_active", true) } // ✅ Tambahkan di sini
        Toast.makeText(this, "📹 Rekaman layar dimulai", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            if (isRecording) {
                stopRecording()
            }
        }, 60_000) // 60.000 ms = 1 menit
    }

    private fun stopRecording() {
        try {
            mediaRecorder.stop()
            isRecording = false
            prefs.edit { putBoolean("recording_active", false) } // ✅ Tambahkan di sini
            Toast.makeText(this, "✅ Rekaman layar berhenti", Toast.LENGTH_SHORT).show()

            val file = File(outputPath)
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(file)
            sendBroadcast(mediaScanIntent)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun openFrontCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        previewView.visibility = View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val barcodeScanner = BarcodeScanning.getClient()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                    barcodeScanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { qrValue ->
                                    if (!hasScannedQRCode) {
                                        hasScannedQRCode = true
                                        previewView.visibility = View.GONE
                                        validateQRCode(qrValue)
                                    }
                                    imageProxy.close()
                                    return@addOnSuccessListener
                                }
                            }
                            imageProxy.close()
                        }
                        .addOnFailureListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal membuka kamera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun validateQRCode(qr: String) {
        val cleaned = qr.trim()
        if (cleaned.isEmpty()) {
            Toast.makeText(this@MainActivity, "⚠️ QR kosong atau tidak terbaca", Toast.LENGTH_SHORT).show()
            hasScannedQRCode = false
            return
        }

        val codeVoucher = if (cleaned.startsWith("http")) {
            try {
                val uri = Uri.parse(cleaned)
                uri.lastPathSegment ?: ""
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "❌ QR tidak valid", Toast.LENGTH_SHORT).show()
                hasScannedQRCode = false
                return
            }
        } else {
            cleaned
        }

        Toast.makeText(this@MainActivity, "📷 QR Terbaca: $codeVoucher", Toast.LENGTH_SHORT).show()

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("code_voucher", codeVoucher)

        RetrofitClient.instance.readVoucher(builder.build())
            .enqueue(object : Callback<VoucherResponse> {
                override fun onResponse(call: Call<VoucherResponse>, response: Response<VoucherResponse>) {
                    if (response.isSuccessful && response.body()?.message == "Voucher berhasil terbaca") {
                        unlockAndGoToWhatsApp(codeVoucher)
                    } else {
                        Toast.makeText(this@MainActivity, "❌ QR tidak valid", Toast.LENGTH_SHORT).show()
                        hasScannedQRCode = false
                    }
                }

                override fun onFailure(call: Call<VoucherResponse>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "⚠️ Gagal koneksi: ${t.message}", Toast.LENGTH_SHORT).show()
                    hasScannedQRCode = false
                }
            })
    }
    /* ShowPasswordlama
    private fun showPasswordDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val dialog = AlertDialog.Builder(this)
            .setTitle("Masukkan Sandi")
            .setMessage("Akses fitur matikan daya membutuhkan verifikasi.")
            .setView(input)
            .setPositiveButton("Lanjut") { _, _ ->
                val enteredPassword = input.text.toString()
                if (enteredPassword == "!Admin123123123") {
                    showRecordingControlDialog()
                } else {
                    Toast.makeText(this, "❌ Sandi salah", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .create()

        dialog.show()
    }*/
    //showpassbaru
    private fun showPasswordDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val dialog = AlertDialog.Builder(this)
            .setTitle("Masukkan Sandi")
            .setMessage("Akses fitur matikan layar membutuhkan verifikasi.")
            .setView(input)
            .setPositiveButton("Lanjut") { _, _ ->
                val enteredPassword = input.text.toString()
                if (enteredPassword == "!Admin12312123") { // ✅ ganti dengan password sebenarnya
                    wrongPasswordCount = 0 // reset jika benar
                    showRecordingControlDialog()
                } else {
                    wrongPasswordCount++
                    Toast.makeText(this, "❌ Sandi salah ($wrongPasswordCount/5)", Toast.LENGTH_SHORT).show()

                    if (wrongPasswordCount >= 5) {
                        wrongPasswordCount = 0 // reset ulang
                        takeIntruderPhoto()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .create()

        dialog.show()
    }
    //membuka kamera saat salah pass
    private fun takeIntruderPhoto() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageCapture = ImageCapture.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            val preview = Preview.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                val previewView = findViewById<PreviewView>(R.id.previewView)
                previewView.visibility = View.VISIBLE
                preview.setSurfaceProvider(previewView.surfaceProvider)

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "intruder_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Toast.makeText(this@MainActivity, "📸 Foto disimpan: ${photoFile.name}", Toast.LENGTH_SHORT).show()

                            // Simpan ke galeri
                            val uri = Uri.fromFile(photoFile)
                            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                            mediaScanIntent.data = uri
                            sendBroadcast(mediaScanIntent)

                            findViewById<PreviewView>(R.id.previewView).visibility = View.GONE
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Toast.makeText(this@MainActivity, "❌ Gagal ambil foto: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

            } catch (e: Exception) {
                Toast.makeText(this, "❌ Error kamera: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun showRecordingControlDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Rekam Layar")

        if (isRecording) {
            builder.setMessage("Rekaman sedang berlangsung. Ingin menghentikan?")
            builder.setPositiveButton("Berhenti") { _, _ ->
                stopService(Intent(this, ScreenRecordService::class.java))
                isRecording = false
                prefs.edit { putBoolean("recording_active", false) }
            }
        } else {
            builder.setMessage("Mulai rekam layar sekarang?")
            builder.setPositiveButton("Mulai") { _, _ ->
                if (storedResultCode != -1 && storedDataIntent != null) {
                    val serviceIntent = Intent(this, ScreenRecordService::class.java)
                    serviceIntent.putExtra("resultCode", storedResultCode)
                    serviceIntent.putExtra("data", storedDataIntent)
                    ContextCompat.startForegroundService(this, serviceIntent)
                    isRecording = true
                    prefs.edit { putBoolean("recording_active", true) }
                } else {
                    val intent = mediaProjectionManager.createScreenCaptureIntent()
                    startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
                }
            }
        }

        builder.setNegativeButton("Batal", null)
        builder.show()
    }
    override fun onPause() {
        super.onPause()

        // Kalau sebelumnya terpinned dan sekarang user keluar
        if (wasPinned && !isRecording) {
            wasPinned = false

            // Start recording
            if (storedResultCode != -1 && storedDataIntent != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(storedResultCode, storedDataIntent!!)
                startScreenRecording()
            } else {
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
            }
        }
    }
}