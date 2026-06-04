package com.audiobookapp.ui.auth

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.audiobookapp.databinding.FragmentLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val googleId   = account.id ?: return@registerForActivityResult
                val email      = account.email ?: ""
                val displayName = account.displayName ?: email
                val idToken    = account.idToken  // available because we called requestIdToken()
                Log.d("GoogleSignIn", "Sign-in OK — id=$googleId email=$email idToken=${idToken?.take(20)}…")
                viewModel.saveGoogleSession(googleId, email, displayName)
                (activity as AuthActivity).startMain()
            } catch (e: ApiException) {
                Log.e("GoogleSignIn", "Sign-in failed, status code: ${e.statusCode}", e)
                val msg = when (e.statusCode) {
                    10   -> "SHA-1 fingerprint mismatch.\n\nIn your Codespaces terminal run:\n\nkeytool -list -v \\\n  -keystore ~/.android/debug.keystore \\\n  -alias androiddebugkey \\\n  -storepass android -keypass android\n\nCopy the SHA1 line → Firebase Console → Project Settings → Your App → Add fingerprint → Save. Then rebuild."
                    12500 -> "Google Sign-In not configured. Make sure google-services.json is the real file from Firebase Console (not the placeholder)."
                    12501 -> "Sign-in was cancelled."
                    7    -> "Network error — check internet connection."
                    else -> "Google Sign-In failed (code ${e.statusCode}). Check Logcat for full details."
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Sign-In Error (code ${e.statusCode})")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        } else {
            Log.w("GoogleSignIn", "Sign-in result code: ${result.resultCode}")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLogin.setOnClickListener {
            viewModel.login(
                binding.etEmail.text.toString(),
                binding.etPassword.text.toString()
            )
        }

        binding.btnGoogleSignIn.setOnClickListener {
            launchGoogleSignIn()
        }

        binding.tvRegister.setOnClickListener {
            (activity as AuthActivity).navigateToRegister()
        }

        viewModel.authResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is AuthResult.Loading -> {
                    binding.btnLogin.isEnabled = false
                    binding.progressBar.visibility = View.VISIBLE
                }
                is AuthResult.Success -> {
                    binding.progressBar.visibility = View.GONE
                    (activity as AuthActivity).startMain()
                }
                is AuthResult.Error -> {
                    binding.btnLogin.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    AlertDialog.Builder(requireContext())
                        .setTitle("Login Error")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun launchGoogleSignIn() {
        // Web Client ID (client_type 3) from google-services.json
        // Required for requestIdToken() — without this, sign-in returns error 10
        val webClientId = "786678251032-sd9tuvtu3l417d3nv4r8b5sukmjgatcg.apps.googleusercontent.com"

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestId()
            .requestProfile()
            .build()

        // Sign out first to force account picker on every tap
        val client = GoogleSignIn.getClient(requireActivity(), gso)
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
