package com.audiobookapp.ui.library

import androidx.navigation.NavDirections
import com.audiobookapp.R

// Hand-written Safe Args directions (replaces generated class when Safe Args plugin is not used)
class LibraryFragmentDirections {
    companion object {
        fun actionLibraryToPlayer(bookId: Int): NavDirections =
            object : NavDirections {
                override val actionId = R.id.action_library_to_player
                override val arguments = androidx.core.os.bundleOf("bookId" to bookId)
            }
    }
}
