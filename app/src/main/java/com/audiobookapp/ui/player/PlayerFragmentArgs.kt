package com.audiobookapp.ui.player

import android.os.Bundle
import androidx.navigation.NavArgs

// Hand-written Safe Args args class (replaces generated class when Safe Args plugin is not used)
data class PlayerFragmentArgs(val bookId: Int) : NavArgs {
    companion object {
        @JvmStatic
        fun fromBundle(bundle: Bundle): PlayerFragmentArgs {
            return PlayerFragmentArgs(bundle.getInt("bookId"))
        }
    }
}
