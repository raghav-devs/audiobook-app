package com.audiobookapp.utils

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object PasswordUtils {
    // Simple SHA-256 + salt approach (sufficient for local on-device storage)
    // For production, consider using BCrypt via a library
    fun hashPassword(password: String): String {
        val salt = generateSalt()
        val hash = sha256("$salt:$password")
        return "$salt:$hash"
    }

    fun verifyPassword(password: String, storedHash: String): Boolean {
        return try {
            val parts = storedHash.split(":")
            if (parts.size != 2) return false
            val salt = parts[0]
            val expectedHash = parts[1]
            val actualHash = sha256("$salt:$password")
            actualHash == expectedHash
        } catch (e: Exception) {
            false
        }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidEmail(email: String): Boolean =
        android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

    fun isValidPassword(password: String): Boolean =
        password.length >= 8
}
