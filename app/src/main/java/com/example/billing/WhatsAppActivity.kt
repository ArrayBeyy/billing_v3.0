package com.example.billing

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.billing.api.config.RetrofitClient
import com.example.billing.api.model.UseVoucherResponse
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.core.net.toUri

class WhatsAppActivity : AppCompatActivity() {

    private var voucherDuration: Int = 1

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whats_app)
        supportActionBar?.hide()

        val etPhone = findViewById<EditText>(R.id.etPhone)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val codeVoucher = intent.getStringExtra("CODE_VOUCHER")

        btnSend.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
                startActivity(intent)
                return@setOnClickListener
            }

            val phone = etPhone.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "Nomor HP tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val currentTime = LocalDateTime.now().format(formatter)

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("code_voucher", codeVoucher ?: "")
                .addFormDataPart("start_time", currentTime)
                .addFormDataPart("phone_number", phone)

            RetrofitClient.instance.useVoucher(builder.build())
                .enqueue(object : Callback<UseVoucherResponse> {
                    override fun onResponse(
                        call: Call<UseVoucherResponse>,
                        response: Response<UseVoucherResponse>
                    ) {
                        if (response.isSuccessful && response.body()?.message == "Voucher berhasil digunakan") {
                            val voucher = response.body()?.voucher
                            voucherDuration = voucher?.duration?.takeIf { it in 1..9999 } ?: 1
                            val startTimeStr = voucher?.start_time ?: currentTime

                            try {
                                val startTime = LocalDateTime.parse(startTimeStr, formatter)
                                val startMillis =
                                    startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val expiryTime = startMillis + (voucherDuration * 1000L)

                                val overlayIntent = Intent(
                                    this@WhatsAppActivity,
                                    TimerOverlayService::class.java
                                ).apply {
                                    putExtra("EXPIRY_TIME", expiryTime)
                                    putExtra("CODE_VOUCHER", codeVoucher)
                                }
                                startService(overlayIntent)

                                // ✅ Buka WhatsApp chat
                                openWhatsAppChat(phone)

                            } catch (e: Exception) {
                                Toast.makeText(
                                    applicationContext,
                                    "Format waktu salah: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                Log.e("WHATSAPP", "Parse error: ${e.message}")
                            }
                        } else {
                            Toast.makeText(
                                applicationContext,
                                "Cek apakah no HP sudah terdaftar atau voucher masih aktif",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e("WHATSAPP", "Response error: ${response.body()?.message}")
                        }
                    }

                    override fun onFailure(call: Call<UseVoucherResponse>, t: Throwable) {
                        Toast.makeText(
                            this@WhatsAppActivity,
                            "Gagal koneksi API: ${t.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e("WHATSAPP", "API failure: ${t.message}")
                    }
                })
        }
    }

    // ✅ Fungsi membuka WhatsApp prioritas: WA biasa → WA bisnis → browser fallback
    private fun openWhatsAppChat(phone: String) {
        val url = "https://wa.me/$phone"
        val uri = Uri.parse(url)

        val waPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

        for (pkg in waPackages) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                setPackage(pkg)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.d("WHATSAPP", "Chat dibuka dengan: $pkg")
                return
            }
        }

        // Fallback terakhir
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(fallbackIntent)
            Log.d("WHATSAPP", "Fallback: membuka WA via browser")
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp.", Toast.LENGTH_SHORT).show()
            Log.e("WHATSAPP", "Gagal buka WA: ${e.message}")
        }
    }
}
