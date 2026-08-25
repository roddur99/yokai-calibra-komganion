package yokai.presentation.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import yokai.data.connection.ConnectionTester
import yokai.data.connection.CredentialStore
import yokai.domain.connection.ConnectionPreferences
import yokai.domain.connection.ConnectionTestResult
import yokai.presentation.theme.Size

internal class GalleryConnectionStep : OnboardingStep {
    private val preferences: ConnectionPreferences by injectLazy()
    private val credentialStore: CredentialStore by injectLazy()
    private val connectionTester: ConnectionTester by injectLazy()

    private var baseUrl by mutableStateOf(preferences.galleryBaseUrl().get())
    private var apiToken by mutableStateOf(credentialStore.galleryApiToken.orEmpty())
    private var testing by mutableStateOf(false)
    private var result by mutableStateOf<ConnectionTestResult?>(null)

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .padding(Size.medium)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Size.small),
        ) {
            Text(
                text = "Connect to Gallery Komganion",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Optional. Connect the Gallery tab to your companion server now, " +
                    "or leave these fields blank and continue.",
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    result = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gallery server URL") },
                placeholder = { Text("http://100.x.x.x:8000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = apiToken,
                onValueChange = {
                    apiToken = it
                    result = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gallery API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )

            Button(
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val tested = connectionTester.testGallery(
                            baseUrl = baseUrl,
                            apiToken = apiToken,
                        )
                        result = tested
                        testing = false

                        if (tested is ConnectionTestResult.Success) {
                            preferences.galleryBaseUrl().set(baseUrl.trim().trimEnd('/'))
                            credentialStore.galleryApiToken = apiToken
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator()
                } else {
                    Text("Test and save connection")
                }
            }

            ConnectionResultText(result)
        }
    }
}
