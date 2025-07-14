package com.example.billing

import android.os.CountDownTimer
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class TimerViewModel : ViewModel() {

    val timeLeft = MutableLiveData<String>()
    private var countDownTimer: CountDownTimer? = null

    /**
     * Memulai countdown berdasarkan expiry timestamp (millis)
     * @param expiryTime waktu kedaluwarsa dalam bentuk waktu Unix millis
     */
    fun startTimer(expiryTime: Long) {
        val currentTime = System.currentTimeMillis()
        val millisUntilFinished = expiryTime - currentTime

        countDownTimer?.cancel()

        // Jika waktu sudah habis
        if (millisUntilFinished <= 0) {
            timeLeft.postValue("Expired")
            return
        }

        countDownTimer = object : CountDownTimer(millisUntilFinished, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                timeLeft.postValue(String.format("%02d:%02d:%02d", hours, minutes, seconds))
            }

            override fun onFinish() {
                timeLeft.postValue("Expired")
            }
        }.start()
    }

    /**
     * Pastikan timer dihentikan saat ViewModel dihancurkan.
     */
    override fun onCleared() {
        countDownTimer?.cancel()
        super.onCleared()
    }
}
