package com.tripleu.updater.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tripleu.updater.ui.screen.AuthGate
import com.tripleu.updater.ui.screen.MainScreen
import com.tripleu.updater.viewmodel.AuthViewModel
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val authViewModel: AuthViewModel = koinViewModel()
            val authState = authViewModel.state.collectAsStateWithLifecycle().value

            AuthGate(
                authState = authState,
                viewModel = authViewModel
            ) {
                MainScreen()
            }
        }
    }
}
