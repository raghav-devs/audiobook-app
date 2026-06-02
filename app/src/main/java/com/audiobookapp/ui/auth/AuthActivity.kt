package com.audiobookapp.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.audiobookapp.databinding.ActivityAuthBinding
import com.audiobookapp.ui.MainActivity
import com.audiobookapp.utils.SessionManager

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        // Auto-navigate if already logged in
        if (sessionManager.isLoggedIn()) {
            startMain()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Default to Login tab
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.authFragmentContainer.id, LoginFragment())
                .commit()
        }
    }

    fun navigateToRegister() {
        supportFragmentManager.beginTransaction()
            .replace(binding.authFragmentContainer.id, RegisterFragment())
            .addToBackStack(null)
            .commit()
    }

    fun navigateToLogin() {
        supportFragmentManager.popBackStack()
    }

    fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
