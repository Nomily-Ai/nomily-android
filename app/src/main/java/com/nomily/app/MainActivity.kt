package com.nomily.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nomily.app.data.AppLocale
import com.nomily.app.ui.NomiApp
import com.nomily.app.ui.theme.NomilyTheme

/**
 * Host for the P0 main UI.
 *
 * Permissions are requested here in one place: API 31+ uses `BLUETOOTH_SCAN` (with `neverForLocation`) + `BLUETOOTH_CONNECT`;
 * on API ≤30 it falls back to `ACCESS_FINE_LOCATION` (under the old model, scanning is considered a location use case).
 *
 * Permissions an action needs only when it runs (notifications for a live session, nearby devices for fast transfer)
 * are gated in `NomiViewModel`: it publishes `permissionRequest`, this Activity owns the launcher and answers back.
 *
 * ⚠️ This debug device (Xiaomi HyperOS) **has adb's `pm grant` disabled**, so runtime permissions can only be granted by the app itself
 * with a user tap — do not rely on pre‑granting via adb.
 */
class MainActivity : ComponentActivity() {

    private val vm: NomiViewModel by viewModels()
    private var permissionPrompt by mutableStateOf<PermissionPrompt?>(null)

    private enum class PermissionPrompt { RETRY, OPEN_SETTINGS }

    private var pendingActionPermission: NomiViewModel.PermissionRequest? = null

    /** Answers whatever the ViewModel asked for; a denial is reported back too, each action decides what that means. */
    private val actionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingActionPermission?.let { request ->
            pendingActionPermission = null
            vm.onPermissionResult(request)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val denied = requiredBlePermissions().filterNot(::isGranted)
        permissionPrompt = when {
            denied.isEmpty() -> null
            denied.all { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) } ->
                PermissionPrompt.OPEN_SETTINGS
            else -> PermissionPrompt.RETRY
        }
    }

    /**
     * UI language takes effect here — must be in `attachBaseContext`, not moved to `onCreate`:
     * resources (`getString`, `stringResource`) are fetched from the Configuration at this layer,
     * by `onCreate` the resources have already been bound to the previous language, and changing it later only updates part of the UI text.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system gesture bar is normally covered by a light scrim (white), while the app's bottom bar is a grouped gray —
        // resulting in an extra white strip at the bottom.
        // Making the system bar fully transparent lets the bottom bar's background extend to the very bottom of the screen.
        enableEdgeToEdge(
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        // On API 29+ the system also adds a contrasting scrim to a transparent navigation bar (the same white strip); it must be disabled explicitly.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        requestBlePermissions()
        setContent {
            NomilyTheme {
                val actionPermission by vm.permissionRequest.collectAsStateWithLifecycle()
                LaunchedEffect(actionPermission) {
                    actionPermission?.let { request ->
                        pendingActionPermission = request
                        actionPermissionLauncher.launch(request.permissions.toTypedArray())
                    }
                }
                NomiApp(vm)
                permissionPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text(stringResource(R.string.bluetooth_permission_title)) },
                        text = {
                            Text(stringResource(
                                if (prompt == PermissionPrompt.OPEN_SETTINGS) {
                                    R.string.bluetooth_permission_settings_message
                                } else {
                                    R.string.bluetooth_permission_message
                                }
                            ))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (prompt == PermissionPrompt.OPEN_SETTINGS) {
                                    openAppSettings()
                                } else {
                                    requestBlePermissions()
                                }
                            }) {
                                Text(stringResource(
                                    if (prompt == PermissionPrompt.OPEN_SETTINGS) {
                                        R.string.bluetooth_permission_open_settings
                                    } else {
                                        R.string.common_retry
                                    }
                                ))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (requiredBlePermissions().all(::isGranted)) permissionPrompt = null
    }

    private fun requiredBlePermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestBlePermissions() {
        val needed = requiredBlePermissions().filterNot(::isGranted)
        if (needed.isEmpty()) {
            permissionPrompt = null
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ))
    }
}
