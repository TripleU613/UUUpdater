@file:Suppress("DEPRECATION")

package com.apkupdater.ui.screen

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.apkupdater.R
import com.apkupdater.data.ui.AuthUiState
import com.apkupdater.util.clickableNoRipple
import com.apkupdater.viewmodel.AuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException

@Composable
fun AuthGate(
    authState: AuthUiState,
    viewModel: AuthViewModel,
    content: @Composable () -> Unit
) {
    when (authState) {
        AuthUiState.Loading -> AuthLoadingScreen()
        is AuthUiState.Authenticated -> content()
        is AuthUiState.RequiresLogin -> LoginScreen(
            viewModel = viewModel,
            message = authState.message
        )
        is AuthUiState.Blocked -> BlockedAccountScreen(
            email = authState.email,
            onSwitchAccount = { viewModel.useAnotherAccount() }
        )
        is AuthUiState.Error -> LoginScreen(
            viewModel = viewModel,
            message = authState.message,
            isError = true
        )
    }
}

@Composable
private fun AuthLoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.authenticating),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    viewModel: AuthViewModel,
    message: String? = null,
    isError: Boolean = false
) {
    val context = LocalContext.current
    val googleClientResult = remember { runCatching { buildGoogleSignInClient(context) } }
    val googleClient = googleClientResult.getOrNull()
    val configError = googleClientResult.exceptionOrNull()

    var isLaunching by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isLaunching = false
        handleGoogleResult(context, result, viewModel)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val iconPainter = rememberAsyncImagePainter(
                ImageRequest.Builder(context)
                    .data(R.mipmap.ic_launcher_round)
                    .crossfade(true)
                    .build()
            )
            Image(
                painter = iconPainter,
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.login_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.login_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            val displayMessage = when {
                configError != null -> stringResource(R.string.login_missing_config)
                message != null -> message
                else -> ""
            }
            if (displayMessage.isNotBlank()) {
                Text(
                    text = displayMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = when {
                        isError || configError != null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            Button(
                onClick = {
                    isLaunching = true
                    googleClient?.let { launcher.launch(it.signInIntent) }
                },
                enabled = !isLaunching && googleClient != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.login_with_google))
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (isLaunching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.login_created_by),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.clickableNoRipple {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://tripleu.org")
                    )
                    context.startActivity(intent)
                },
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BlockedAccountScreen(
    email: String,
    onSwitchAccount: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.account_blocked_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.account_blocked_body, email),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onSwitchAccount) {
                Text(text = stringResource(R.string.switch_account))
            }
        }
    }
}

private fun buildGoogleSignInClient(context: Context): GoogleSignInClient {
    val resourceId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    require(resourceId != 0) {
        "Missing default_web_client_id resource. Ensure google-services.json is present and the Google Services Gradle plugin has run."
    }
    val serverId = context.getString(resourceId)
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(serverId)
        .requestEmail()
        .build()
    return GoogleSignIn.getClient(context, options)
}

private fun handleGoogleResult(
    context: Context,
    result: ActivityResult,
    viewModel: AuthViewModel
) {
    val data = result.data
    val task = GoogleSignIn.getSignedInAccountFromIntent(data)
    try {
        val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            viewModel.onGoogleSignInError(context.getString(R.string.login_google_developer_error))
        } else {
            viewModel.onGoogleIdTokenReceived(idToken, context.getString(R.string.login_google_failed))
        }
    } catch (error: ApiException) {
        val message = when (error.statusCode) {
            GoogleSignInStatusCodes.DEVELOPER_ERROR -> context.getString(R.string.login_google_developer_error)
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> context.getString(R.string.login_google_canceled)
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> context.getString(R.string.login_google_failed)
            else -> context.getString(R.string.login_google_failed)
        }
        viewModel.onGoogleSignInError(message)
    } catch (error: Exception) {
            viewModel.onGoogleSignInError(context.getString(R.string.login_google_failed))
    }
}
