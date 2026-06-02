package com.audiobookapp.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.audiobookapp.databinding.FragmentRegisterBinding

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRegister.setOnClickListener {
            viewModel.register(
                binding.etDisplayName.text.toString(),
                binding.etEmail.text.toString(),
                binding.etPassword.text.toString(),
                binding.etConfirmPassword.text.toString()
            )
        }

        binding.tvLogin.setOnClickListener {
            (activity as AuthActivity).navigateToLogin()
        }

        viewModel.authResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AuthResult.Loading -> {
                    binding.btnRegister.isEnabled = false
                    binding.progressBar.visibility = View.VISIBLE
                }
                is AuthResult.Success -> {
                    binding.progressBar.visibility = View.GONE
                    (activity as AuthActivity).startMain()
                }
                is AuthResult.Error -> {
                    binding.btnRegister.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
