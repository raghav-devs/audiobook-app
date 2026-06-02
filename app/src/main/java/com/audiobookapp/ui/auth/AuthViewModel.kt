package com.audiobookapp.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.audiobookapp.data.db.AppDatabase
import com.audiobookapp.data.model.User
import com.audiobookapp.utils.PasswordUtils
import com.audiobookapp.utils.SessionManager
import kotlinx.coroutines.launch

sealed class AuthResult {
    object Loading : AuthResult()
    data class Success(val displayName: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val sessionManager = SessionManager(application)

    private val _authResult = MutableLiveData<AuthResult>()
    val authResult: LiveData<AuthResult> = _authResult

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _authResult.value = AuthResult.Error("Please fill all fields")
            return
        }
        if (!PasswordUtils.isValidEmail(email)) {
            _authResult.value = AuthResult.Error("Invalid email address")
            return
        }
        _authResult.value = AuthResult.Loading
        viewModelScope.launch {
            val user = db.userDao().getUserByEmail(email.lowercase().trim())
            if (user == null) {
                _authResult.postValue(AuthResult.Error("No account found with this email"))
                return@launch
            }
            if (!PasswordUtils.verifyPassword(password, user.passwordHash)) {
                _authResult.postValue(AuthResult.Error("Incorrect password"))
                return@launch
            }
            sessionManager.saveLocalSession(user.id, user.email, user.displayName)
            _authResult.postValue(AuthResult.Success(user.displayName))
        }
    }

    fun register(displayName: String, email: String, password: String, confirmPassword: String) {
        if (displayName.isBlank() || email.isBlank() || password.isBlank()) {
            _authResult.value = AuthResult.Error("Please fill all fields")
            return
        }
        if (!PasswordUtils.isValidEmail(email)) {
            _authResult.value = AuthResult.Error("Invalid email address")
            return
        }
        if (!PasswordUtils.isValidPassword(password)) {
            _authResult.value = AuthResult.Error("Password must be at least 8 characters")
            return
        }
        if (password != confirmPassword) {
            _authResult.value = AuthResult.Error("Passwords do not match")
            return
        }
        _authResult.value = AuthResult.Loading
        viewModelScope.launch {
            val existing = db.userDao().getUserByEmail(email.lowercase().trim())
            if (existing != null) {
                _authResult.postValue(AuthResult.Error("An account with this email already exists"))
                return@launch
            }
            val user = User(
                email = email.lowercase().trim(),
                passwordHash = PasswordUtils.hashPassword(password),
                displayName = displayName.trim()
            )
            val id = db.userDao().insertUser(user)
            sessionManager.saveLocalSession(id.toInt(), user.email, user.displayName)
            _authResult.postValue(AuthResult.Success(user.displayName))
        }
    }

    fun saveGoogleSession(googleId: String, email: String, displayName: String) {
        sessionManager.saveGoogleSession(googleId, email, displayName)
    }
}
