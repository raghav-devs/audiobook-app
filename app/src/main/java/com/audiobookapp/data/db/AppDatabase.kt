package com.audiobookapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.audiobookapp.data.model.AudioBook
import com.audiobookapp.data.model.User

@Database(
    entities = [User::class, AudioBook::class],
    version = 2,           // bumped from 1 → 2 for new columns
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun audioBookDao(): AudioBookDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "audiobookapp.db"
                )
                .fallbackToDestructiveMigration()  // dev mode: drop & recreate on schema change
                .build()
                .also { INSTANCE = it }
            }
    }
}
