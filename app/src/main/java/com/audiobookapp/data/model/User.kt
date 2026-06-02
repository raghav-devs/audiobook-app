package com.audiobookapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val email: String,
    val passwordHash: String,  // bcrypt-style hash via MessageDigest
    val displayName: String,
    val createdAt: Long = System.currentTimeMillis()
)
