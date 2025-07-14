package com.example.billing

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import cn.iwgang.countdownview.CountdownView
import com.example.billing.api.config.RetrofitClient
import com.example.billing.api.model.VoucherResponse
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CountdownActivity : AppCompatActivity() {

    lateinit var mCountdownView: CountdownView

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_countdown)

        val codeVoucher = intent.getStringExtra("CODE_VOUCHER") ?: return
        mCountdownView = findViewById(R.id.countdownView)

        // Ambil start_time dan duration dari server
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("code_voucher", codeVoucher)

        RetrofitClient.instance.readVoucher(builder.build())
            .enqueue(object : Callback<VoucherResponse> {
                override fun onResponse(
                    call: Call<VoucherResponse>,
                    response: Response<VoucherResponse>
                ) {
                    if (response.isSuccessful && response.body()?.voucher != null) {
                        val voucher = response.body()!!.voucher
                        val durationInSeconds = voucher.duration
                        val startTimeStr = voucher.start_time

                        if (startTimeStr == null) {
                            Toast.makeText(this@CountdownActivity, "Start time kosong", Toast.LENGTH_SHORT).show()
                            return
                        }

                        try {
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            val startTime = LocalDateTime.parse(startTimeStr, formatter)
                            val startMillis = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val nowMillis = System.currentTimeMillis()
                            val expiryMillis = startMillis + durationInSeconds * 1000L
                            val millisLeft = expiryMillis - nowMillis

                            if (millisLeft <= 0) {
                                Toast.makeText(this@CountdownActivity, "Voucher sudah kedaluwarsa", Toast.LENGTH_SHORT).show()
                                finish()
                            } else {
                                mCountdownView.start(millisLeft)
                                mCountdownView.setOnCountdownEndListener {
                                    stopVoucher(codeVoucher)
                                }
                            }

                        } catch (e: Exception) {
                            Toast.makeText(this@CountdownActivity, "Format waktu salah: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                    } else {
                        Toast.makeText(this@CountdownActivity, "Voucher tidak ditemukan", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<VoucherResponse>, t: Throwable) {
                    Toast.makeText(this@CountdownActivity, "Gagal koneksi API: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopVoucher(codeVoucher: String) {
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
                        val backToMain = Intent(this@CountdownActivity, MainActivity::class.java)
                        backToMain.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(backToMain)
                    } else {
                        Toast.makeText(applicationContext, "Gagal stop voucher", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<VoucherResponse>, t: Throwable) {
                    Toast.makeText(this@CountdownActivity, "Gagal koneksi API: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
