package com.example.galerinio.presentation.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.galerinio.R

class SplashActivity : AppCompatActivity() {

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Fullscreen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_splash)

        val videoView = findViewById<VideoView>(R.id.splashVideo)
        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.splash_video}")
        videoView.setVideoURI(videoUri)

        videoView.setOnCompletionListener {
            launchMain()
        }

        videoView.setOnErrorListener { _, _, _ ->
            launchMain()
            true
        }

        videoView.start()
    }

    private fun launchMain() {
        if (launched) return
        launched = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
