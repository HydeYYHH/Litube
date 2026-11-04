package com.hhst.youtubelite.account

import android.content.Intent
import android.os.Bundle
import com.hhst.youtubelite.R
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.hhst.youtubelite.account.data.MediaSignInService
import com.hhst.youtubelite.account.domain.SignInAction
import com.hhst.youtubelite.account.domain.SignInUiState
import com.hhst.youtubelite.account.presentation.SignInViewModel
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.data.media.PreferencesAuthStore
import com.hhst.youtubelite.ui.components.AppTextButton
import com.hhst.youtubelite.ui.components.AppTopAppBar
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.theme.AppStyle

/**
 * Displays a device code while the account is confirmed in a browser.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class SignInActivity : com.hhst.youtubelite.ui.NativeActivity() {

    /** The page the user must open; exposed for instrumentation. */
    var currentUrl: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixtures = ContentRepositories.fixtures(intent)
        if (fixtures) currentUrl = FIXTURE_URL
        val model = androidx.lifecycle.ViewModelProvider(this,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == SignInViewModel::class.java)
                    val service = if (fixtures) object : com.hhst.youtubelite.account.data.SignInService {
                        override suspend fun loadCode() = com.liskovsoft.youtubeapi.videoinfo.V2.SignInCode(
                            "fixture", FIXTURE_CODE, FIXTURE_URL, Long.MAX_VALUE, 1000)
                        override suspend fun poll(code: com.liskovsoft.youtubeapi.videoinfo.V2.SignInCode):
                            com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken = kotlinx.coroutines.awaitCancellation()
                        override suspend fun complete(token: com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken) =
                            emptyList<com.liskovsoft.youtubeapi.videoinfo.V2.Account>()
                    } else MediaSignInService(
                        org.koin.java.KoinJavaComponent.getKoin().get<okhttp3.OkHttpClient>(), PreferencesAuthStore(this@SignInActivity))
                    return SignInViewModel(service) as T
                }
            })[SignInViewModel::class.java]
        setAppContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            androidx.compose.runtime.SideEffect {
                currentUrl = (state as? SignInUiState.Ready)?.verificationUrl
            }
            LaunchedEffect(state) { if (state == SignInUiState.Success) finish() }
            BackHandler { finish() }
            Column(Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing).testTag("signin-screen")) {
                AppTopAppBar(title = { AppText("Sign in", size = 18, line = 24, weight = FontWeight.Medium) })
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    when (val current = state) {
                        SignInUiState.Loading -> StatusBlock("Contacting YouTube…", tag = "signin-loading")
                        SignInUiState.Completing -> StatusBlock("Finishing sign-in…", tag = "signin-completing")
                        SignInUiState.Success -> StatusBlock("Signed in", tag = "signin-success", spinner = false)
                        is SignInUiState.Error -> ErrorBlock(current.message) { model.onAction(SignInAction.Retry) }
                        is SignInUiState.Ready -> ReadyBlock(current)
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusBlock(text: String, tag: String, spinner: Boolean = true) {
        Column(Modifier.fillMaxWidth().padding(top = 96.dp).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinner) {
                CircularProgressIndicator(Modifier.size(32.dp), color = AppStyle.Action, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
            }
            AppText(text, size = 15, line = 22, color = DesignTokens.Secondary)
        }
    }

    @Composable
    private fun ErrorBlock(message: String, onRetry: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 96.dp).testTag("signin-error"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(message, size = 15, line = 22, color = DesignTokens.Ink)
            Spacer(Modifier.height(16.dp))
            AppTextButton(onClick = onRetry, modifier = Modifier.testTag("signin-retry")) {
                AppText("Try again", size = 14, line = 20, weight = FontWeight.Medium, color = AppStyle.Action)
            }
        }
    }

    @Composable
    private fun ReadyBlock(state: SignInUiState.Ready) {
        val clipboard = LocalClipboardManager.current
        Column(Modifier.fillMaxWidth().testTag("signin-ready")) {
            AppText("Sign in with your Google account", size = 20, line = 26, weight = FontWeight.Medium)
            Spacer(Modifier.height(24.dp))
            StepRow(1, "Open ${state.verificationUrl.toUri().host ?: state.verificationUrl} on your phone or computer")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openBrowser(state.verificationUrl) },
                shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = AppStyle.Action),
                modifier = Modifier.testTag("signin-open")) {
                AppText("Open in browser", size = 14, line = 20, weight = FontWeight.Medium,
                    color = androidx.compose.ui.graphics.Color.White)
            }
            Spacer(Modifier.height(24.dp))
            StepRow(2, "Enter this code")
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().background(DesignTokens.Surface, RoundedCornerShape(12.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                AppText(state.userCode, size = 28, line = 34, weight = FontWeight.Bold,
                    modifier = Modifier.testTag("signin-code"))
                AppTextButton(onClick = { clipboard.setText(AnnotatedString(state.userCode)) },
                    modifier = Modifier.testTag("signin-copy")) {
                    AppText("Copy code", size = 14, line = 20, weight = FontWeight.Medium, color = AppStyle.Action)
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = AppStyle.Action, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                StepRow(3, "This page continues on its own once you confirm.")
            }
        }
    }

    @Composable
    private fun StepRow(number: Int, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(DesignTokens.Surface, CircleShape), contentAlignment = Alignment.Center) {
                AppText(number.toString(), size = 13, line = 16, weight = FontWeight.Medium)
            }
            Spacer(Modifier.width(12.dp))
            AppText(text, size = 14, line = 20, color = DesignTokens.Ink)
        }
    }

    companion object {
        internal const val FIXTURE_CODE = "ABCD-EFGH"
        internal const val FIXTURE_URL = "https://www.youtube.com/activate"
    }

    private fun openBrowser(url: String) {
        val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeUnless { it == packageName || it == "android" }
            ?: packageManager.queryIntentActivities(probe, 0).firstOrNull {
                it.activityInfo.packageName != packageName
            }?.activityInfo?.packageName
        val intent = browser?.let {
            Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(it)
        }
        if (intent == null || runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.sign_in_no_browser,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
