package com.example.billing

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.billing.api.config.RetrofitClient
import com.example.billing.api.model.VoucherResponse
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TimerOverlayActivity : AppCompatActivity() {

    private lateinit var timerViewModel: TimerViewModel
    private lateinit var overlayLayout: FrameLayout
    private lateinit var timerText: TextView
    private var codeVoucher: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer_overlay)

        overlayLayout = findViewById(R.id.overlayLayout)
        timerText = findViewById(R.id.timerText)
        timerViewModel = ViewModelProvider(this)[TimerViewModel::class.java]

        // Ambil data dari intent
        val expiryTime = intent.getLongExtra("EXPIRY_TIME", 0L)
        /*codeVoucher = intent.getStringExtra("CODE_VOUCHER") ?: ""*/
        val now = System.currentTimeMillis()

        if (expiryTime > now) {
            timerViewModel.startTimer(expiryTime)
        } else {
            timerText.text = "Expired"
            overlayLayout.visibility = View.GONE
            stopVoucherNow() // Langsung stop jika waktu sudah habis
        }

        timerViewModel.timeLeft.observe(this) { time ->
            timerText.text = time
            if (time == "Expired") {
                overlayLayout.visibility = View.GONE
                stopVoucherNow()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopVoucherNow() {
        if (codeVoucher.isEmpty()) return

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val currentTime = LocalDateTime.now().format(formatter)

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("code_voucher", codeVoucher)
            .addFormDataPart("time_stop", currentTime)

        RetrofitClient.instance.stopVoucher(builder.build())
            .enqueue(object : Callback<VoucherResponse> {
                override fun onResponse(call: Call<VoucherResponse>, response: Response<VoucherResponse>) {
                    if (response.isSuccessful && response.body()?.message == "Voucher berhasil distop") {
                        Toast.makeText(this@TimerOverlayActivity, "Voucher dihentikan", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@TimerOverlayActivity, "Gagal stop voucher", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<VoucherResponse>, t: Throwable) {
                    Toast.makeText(this@TimerOverlayActivity, "Gagal koneksi API: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
