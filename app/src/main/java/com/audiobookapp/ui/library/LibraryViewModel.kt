package com.audiobookapp.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.audiobookapp.data.db.AppDatabase
import com.audiobookapp.data.model.AudioBook
import com.audiobookapp.utils.SessionManager

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val session = SessionManager(application)

    val books: LiveData<List<AudioBook>> = when {
        session.getAuthType() == SessionManager.AUTH_TYPE_GOOGLE ->
            db.audioBookDao().getBooksByGoogleUser(session.getGoogleUserId())
        else ->
            db.audioBookDao().getBooksByLocalUser(session.getLocalUserId())
    }

    val displayName: String get() = session.getDisplayName()
    val email: String get() = session.getEmail()
}
