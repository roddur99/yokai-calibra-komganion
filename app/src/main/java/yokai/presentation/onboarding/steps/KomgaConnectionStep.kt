package yokai.presentation.onboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import yokai.data.connection.ConnectionTester
import yokai.data.connection.CredentialStore
import yokai.domain.connection.ConnectionPreferences
import yokai.domain.connection.ConnectionTestResult
import yokai.domain.connection.KomgaAuthMethod
import yokai.presentation.theme.Size

internal class KomgaConnectionStep : OnboardingStep {
    private val preferences: ConnectionPreferences by injectLazy()
    private val credentialStore: CredentialStore by injectLazy()
    private val connectionTester: ConnectionTester by injectLazy()

    private var baseUrl by mutableStateOf(preferences.komgaBaseUrl().get())
    private var authMethod by mutableStateOf(preferences.komgaAuthMethod().get())
    private var apiKey by mutableStateOf(credentialStore.komgaApiKey.orEmpty())
    private var username by mutableStateOf(preferences.komgaUsername().get())
    private var password by mutableStateOf(credentialStore.komgaPassword.orEmpty())
    private var testing by mutableStateOf(false)
    private var result by mutableStateOf<ConnectionTestResult?>(null)
    private var connected by mutableStateOf(false)

    override val isComplete: Boolean
        get() = connected

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .padding(Size.medium)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Size.small),
        ) {
            Text(
                text = "Connect to Komga",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Enter the address you use to open Komga, including http:// or https://. " +
                    "A Komga API key is recommended.",
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    invalidateResult()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Komga server URL") },
                placeholder = { Text("http://100.x.x.x:25600") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            AuthChoice(
                label = "API key",
                selected = authMethod == KomgaAuthMethod.API_KEY,
                onClick = {
                    authMethod = KomgaAuthMethod.API_KEY
                    invalidateResult()
                },
            )
            AuthChoice(
                label = "Username and password",
                selected = authMethod == KomgaAuthMethod.BASIC,
                onClick = {
                    authMethod = KomgaAuthMethod.BASIC
                    invalidateResult()
                },
            )

            if (authMethod == KomgaAuthMethod.API_KEY) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        invalidateResult()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Komga API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            } else {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        invalidateResult()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        invalidateResult()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            Button(
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val tested = connectionTester.testKomga(
                            baseUrl = baseUrl,
                            authMethod = authMethod,
                            apiKey = apiKey,
                            username = username,
                            password = password,
                        )
                        result = tested
                        testing = false

                        if (tested is ConnectionTestResult.Success) {
                            saveConnection()
                            connected = true
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

    @Composable
    private fun AuthChoice(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Text(label)
        }
    }

    private fun invalidateResult() {
        connected = false
        result = null
    }

    private fun saveConnection() {
        preferences.komgaBaseUrl().set(baseUrl.trim().trimEnd('/'))
        preferences.komgaAuthMethod().set(authMethod)
        preferences.komgaUsername().set(username.trim())

        when (authMethod) {
            KomgaAuthMethod.API_KEY -> {
                credentialStore.komgaApiKey = apiKey
                credentialStore.komgaPassword = null
            }
            KomgaAuthMethod.BASIC -> {
                credentialStore.komgaApiKey = null
                credentialStore.komgaPassword = password
            }
        }
    }
}

@Composable
internal fun ConnectionResultText(result: ConnectionTestResult?) {
    when (result) {
        ConnectionTestResult.Success -> Text(
            text = "Connection successful",
            color = MaterialTheme.colorScheme.primary,
        )
        is ConnectionTestResult.Failure -> Text(
            text = result.message,
            color = MaterialTheme.colorScheme.error,
        )
        null -> Unit
    }
}
