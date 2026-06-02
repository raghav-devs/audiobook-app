package com.audiobookapp.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiobookapp.R
import com.audiobookapp.databinding.FragmentLibraryBinding

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()
    private lateinit var adapter: AudioBookAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvGreeting.text = "Hello, ${viewModel.displayName}"

        adapter = AudioBookAdapter { book ->
            val action = LibraryFragmentDirections.actionLibraryToPlayer(book.id)
            findNavController().navigate(action)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.fabAddBook.setOnClickListener {
            findNavController().navigate(R.id.action_library_to_converter)
        }

        viewModel.books.observe(viewLifecycleOwner) { books ->
            adapter.submitList(books)
            binding.tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
