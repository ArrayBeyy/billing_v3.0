package com.example.billing

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.billing.api.config.RetrofitClient
import com.example.billing.api.model.VoucherResponse
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TimerOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var timerText: TextView
    private lateinit var stopButton: Button
    private var timer: CountDownTimer? = null
    private var codeVoucher = ""

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.activity_timer_overlay_service, null)

        timerText = overlayView.findViewById(R.id.timerText)
        stopButton = overlayView.findViewById(R.id.stopButton)

        stopButton.setOnClickListener {
            stopVoucherNow()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(overlayView, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            codeVoucher = intent.getStringExtra("CODE_VOUCHER") ?: ""
            val expiryTime = intent.getLongExtra("EXPIRY_TIME", 0L)
            val now = System.currentTimeMillis()
            val millisLeft = expiryTime - now

            Log.d("TimerOverlayService", "expiryTime: $expiryTime, now: $now, millisLeft: $millisLeft")

            if (codeVoucher.isNotEmpty() && millisLeft > 0) {
                startTimer(millisLeft)
            } else {
                Toast.makeText(this, "Voucher sudah habis waktunya atau tidak valid", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTimer(millis: Long) {
        timer?.cancel() // Pastikan tidak ada timer lama yang berjalan
        timer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                timerText.text = String.format("Timer: %02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                timerText.text = "Selesai!"
                stopVoucherNow()
            }
        }.start()
    }

    @SuppressLint("NewApi")
    private fun stopVoucherNow() {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val current = LocalDateTime.now().format(formatter)

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("code_voucher", codeVoucher)
            .addFormDataPart("time_stop", current)

        RetrofitClient.instance.stopVoucher(builder.build())
            .enqueue(object : Callback<VoucherResponse> {
                override fun onResponse(call: Call<VoucherResponse>, response: Response<VoucherResponse>) {
                    if (response.isSuccessful && response.body()?.message == "Voucher berhasil distop") {
                        val backToMain = Intent(this@TimerOverlayService, MainActivity::class.java)
                        backToMain.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(backToMain)
                    } else {
                        Toast.makeText(applicationContext, "Gagal stop voucher", Toast.LENGTH_SHORT).show()
                    }
                    stopSelf()
                }

                override fun onFailure(call: Call<VoucherResponse>, t: Throwable) {
                    Toast.makeText(this@TimerOverlayService, "Gagal koneksi API: ${t.message}", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            })
    }

    override fun onDestroy() {
        timer?.cancel()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        super.onDestroy()
    }
}

