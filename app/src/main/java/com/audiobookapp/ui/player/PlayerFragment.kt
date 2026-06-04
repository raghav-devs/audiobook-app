package com.audiobookapp.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.audiobookapp.databinding.FragmentPlayerBinding
import java.util.concurrent.TimeUnit

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by viewModels()
    private val args: PlayerFragmentArgs by navArgs()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.loadBook(args.bookId)

        // Book metadata
        viewModel.book.observe(viewLifecycleOwner) { book ->
            book ?: return@observe
            binding.tvBookTitle.text = book.title
            binding.tvFileType.text = book.fileType
        }

        // Player status
        viewModel.status.observe(viewLifecycleOwner) { status ->
            when (status) {
                is PlayerStatus.Idle -> {
                    setControlsEnabled(false)
                    binding.streamingBanner.visibility = View.GONE
                }
                is PlayerStatus.Loading -> {
                    setControlsEnabled(false)
                    binding.streamingBanner.visibility = View.GONE
                    binding.tvStreamingLabel.text = "Loading…"
                }
                is PlayerStatus.Ready -> {
                    setControlsEnabled(true)
                    binding.streamingBanner.visibility = View.GONE
                }
                is PlayerStatus.StreamingConversion -> {
                    // Controls enabled — user can play what's already been converted
                    setControlsEnabled(true)
                    binding.streamingBanner.visibility = View.VISIBLE
                    binding.tvStreamingLabel.text =
                        "⚡ Converting in background… ${status.conversionProgress}% — you can play what's ready"
                    binding.streamingProgress.progress = status.conversionProgress
                }
                is PlayerStatus.Error -> {
                    setControlsEnabled(false)
                    binding.streamingBanner.visibility = View.VISIBLE
                    binding.streamingProgress.visibility = View.GONE
                    binding.tvStreamingLabel.text = "⚠ ${status.message}"
                }
            }
        }

        // Play/Pause state
        viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            binding.btnPlayPause.setImageResource(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        // Duration
        viewModel.durationMs.observe(viewLifecycleOwner) { duration ->
            if (duration > 0) {
                binding.seekBar.max = (duration / 1000).toInt()
                binding.tvDuration.text = formatTime(duration)
            }
        }

        // Current position
        viewModel.currentPositionMs.observe(viewLifecycleOwner) { position ->
            binding.seekBar.progress = (position / 1000).toInt()
            binding.tvCurrentTime.text = formatTime(position)
        }

        // Controls
        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnSkipBack.setOnClickListener { viewModel.skipBack() }
        binding.btnSkipForward.setOnClickListener { viewModel.skipForward() }

        // SeekBar
        binding.seekBar.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrentTime.text = formatTime(progress * 1000L)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                sb?.let { viewModel.seekTo(it.progress * 1000L) }
            }
        })

        // Playback speed cycle
        binding.btnSpeed.setOnClickListener {
            val current = viewModel.player.playbackParameters.speed
            val next = when {
                current < 0.8f -> 1.0f
                current < 1.1f -> 1.25f
                current < 1.4f -> 1.5f
                current < 1.6f -> 2.0f
                else -> 0.75f
            }
            viewModel.player.setPlaybackSpeed(next)
            binding.btnSpeed.text = "${next}×"
        }

        // Download to device
        binding.btnDownload.setOnClickListener {
            viewModel.exportToDevice()
        }

        // Share via system sheet
        binding.btnShare.setOnClickListener {
            viewModel.prepareShare()
        }

        // Export state observer
        viewModel.exportState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ExportState.Idle -> {
                    binding.tvExportStatus.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                    binding.btnShare.isEnabled = true
                }
                is ExportState.Exporting -> {
                    binding.tvExportStatus.visibility = View.VISIBLE
                    binding.tvExportStatus.text = "Saving…"
                    binding.btnDownload.isEnabled = false
                }
                is ExportState.Done -> {
                    binding.btnDownload.isEnabled = true
                    binding.tvExportStatus.visibility = View.VISIBLE
                    binding.tvExportStatus.text = "✓ Saved to ${state.savedPath}"
                    // Auto-hide after 4 seconds
                    binding.tvExportStatus.postDelayed({
                        binding.tvExportStatus.visibility = View.GONE
                        viewModel.resetExportState()
                    }, 4000)
                }
                is ExportState.ReadyToShare -> {
                    viewModel.resetExportState()
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = state.mimeType
                        putExtra(android.content.Intent.EXTRA_STREAM, state.uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Share audiobook via…"))
                }
                is ExportState.Error -> {
                    binding.btnDownload.isEnabled = true
                    binding.btnShare.isEnabled = true
                    binding.tvExportStatus.visibility = View.VISIBLE
                    binding.tvExportStatus.text = "⚠ ${state.message}"
                    binding.tvExportStatus.postDelayed({
                        binding.tvExportStatus.visibility = View.GONE
                        viewModel.resetExportState()
                    }, 5000)
                }
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnPlayPause.isEnabled = enabled
        binding.btnSkipBack.isEnabled  = enabled
        binding.btnSkipForward.isEnabled = enabled
        binding.seekBar.isEnabled = enabled
        binding.btnSpeed.isEnabled = enabled
    }

    private fun formatTime(ms: Long): String {
        val hours   = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
               else "%d:%02d".format(minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
