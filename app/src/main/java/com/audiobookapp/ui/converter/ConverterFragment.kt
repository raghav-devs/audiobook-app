package com.audiobookapp.ui.converter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.audiobookapp.R
import com.audiobookapp.databinding.FragmentConverterBinding

class ConverterFragment : Fragment() {

    private var _binding: FragmentConverterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConverterViewModel by viewModels()

    private var selectedUri: Uri? = null
    private var selectedFileName: String = ""

    // Supported extensions — validated after selection, not by MIME filter
    // (MIME filters are unreliable across Android file managers and Google Drive)
    private val supportedExtensions = setOf("pdf", "docx", "doc", "txt", "epub", "rtf")

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentConverterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickLocal.setOnClickListener { openFilePicker() }
        binding.btnPickDrive.setOnClickListener { openFilePicker() } // SAF includes Drive automatically

        binding.btnConvert.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            viewModel.startConversion(uri, selectedFileName)
        }

        // User can navigate to Library any time — conversion keeps running in background
        binding.btnGoLibrary.setOnClickListener {
            findNavController().navigate(R.id.action_converter_to_library)
        }

        viewModel.state.observe(viewLifecycleOwner) { state -> updateUI(state) }
    }

    private fun openFilePicker() {
        // Use */* to let ALL file managers show all files.
        // We validate the extension ourselves after selection.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Hint to show cloud providers (Drive appears automatically via SAF)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        filePicker.launch(intent)
    }

    private fun handleSelectedFile(uri: Uri) {
        val name = getFileName(uri)
        val ext = name.substringAfterLast('.').lowercase()

        if (ext !in supportedExtensions) {
            AlertDialog.Builder(requireContext())
                .setTitle("Unsupported File Type")
                .setMessage(".$ext files are not supported.\n\nSupported formats: PDF, DOCX, TXT, EPUB, RTF")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        selectedUri = uri
        selectedFileName = name
        viewModel.onFileSelected(uri, name)
    }

    private fun updateUI(state: ConversionState) {
        when (state) {
            is ConversionState.Idle -> {
                binding.cardFileInfo.visibility = View.GONE
                binding.btnConvert.isEnabled = false
                binding.progressGroup.visibility = View.GONE
                binding.btnConvert.text = "Convert to Audio"
                binding.btnPickLocal.isEnabled = true
                binding.btnPickDrive.isEnabled = true
            }
            is ConversionState.FileSelected -> {
                binding.cardFileInfo.visibility = View.VISIBLE
                binding.tvSelectedFile.text = state.fileName
                binding.btnConvert.isEnabled = true
                binding.progressGroup.visibility = View.GONE
            }
            is ConversionState.Extracting -> {
                binding.btnConvert.isEnabled = false
                binding.btnPickLocal.isEnabled = false
                binding.btnPickDrive.isEnabled = false
                binding.progressGroup.visibility = View.VISIBLE
                // state.fileName doubles as a label (may say "OCR scanning page X of Y…")
                binding.tvProgressLabel.text = state.fileName
                binding.progressBar.isIndeterminate = true
            }
            is ConversionState.Converting -> {
                binding.progressGroup.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = state.progress
                binding.tvProgressLabel.text = "Converting to audio… ${state.progress}%"
                // Show "Go to Library" button while converting so user can navigate away
                binding.btnGoLibrary.visibility = View.VISIBLE
            }
            is ConversionState.Done -> {
                binding.btnGoLibrary.visibility = View.GONE
                binding.progressGroup.visibility = View.GONE
                viewModel.reset()
                findNavController().navigate(R.id.action_converter_to_library)
            }
            is ConversionState.Error -> {
                binding.progressGroup.visibility = View.GONE
                binding.btnConvert.isEnabled = selectedUri != null
                binding.btnPickLocal.isEnabled = true
                binding.btnPickDrive.isEnabled = true
                binding.btnGoLibrary.visibility = View.GONE
                AlertDialog.Builder(requireContext())
                    .setTitle("Conversion Error")
                    .setMessage(state.message)
                    .setPositiveButton("OK", null)
                    .show()
                viewModel.reset()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "document.txt"
        try {
            val cursor = requireContext().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            name = uri.lastPathSegment ?: "document.txt"
        }
        return name
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
