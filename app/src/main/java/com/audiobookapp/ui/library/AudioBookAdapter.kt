package com.audiobookapp.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobookapp.data.model.AudioBook
import com.audiobookapp.databinding.ItemAudiobookBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AudioBookAdapter(
    private val onItemClick: (AudioBook) -> Unit
) : ListAdapter<AudioBook, AudioBookAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemAudiobookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(book: AudioBook) {
            binding.tvTitle.text = book.title
            binding.tvFileType.text = book.fileType.uppercase()
            binding.tvDuration.text = formatDuration(book.durationMs)
            binding.tvDate.text = formatDate(book.createdAt)

            if (book.isConversionComplete) {
                binding.tvStatus.text = "Ready"
                binding.progressConversion.visibility = android.view.View.GONE
            } else {
                binding.tvStatus.text = "Converting… ${book.conversionProgress}%"
                binding.progressConversion.visibility = android.view.View.VISIBLE
                binding.progressConversion.progress = book.conversionProgress
            }

            // Progress bar for listening progress
            if (book.durationMs > 0) {
                val progress = ((book.lastPositionMs.toFloat() / book.durationMs) * 100).toInt()
                binding.progressListening.progress = progress
            }

            binding.root.setOnClickListener {
                if (book.isConversionComplete) onItemClick(book)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAudiobookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun formatDuration(ms: Long): String {
        if (ms == 0L) return "--:--"
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
               else "%d:%02d".format(minutes, seconds)
    }

    private fun formatDate(ms: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    class DiffCallback : DiffUtil.ItemCallback<AudioBook>() {
        override fun areItemsTheSame(a: AudioBook, b: AudioBook) = a.id == b.id
        override fun areContentsTheSame(a: AudioBook, b: AudioBook) = a == b
    }
}
