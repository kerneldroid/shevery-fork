package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.SwitchSettingsRow

class LabFeaturesActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
            var verboseLogging by remember { mutableStateOf(ModuleSettings.isVerboseLogging()) }
            var notifyRecovery by remember { mutableStateOf(ModuleSettings.isNotifyOnRecovery()) }
            var autoRefresh by remember { mutableStateOf(ModuleSettings.isAutoRefreshOnResume()) }
            var showUnsafeDialog by remember { mutableStateOf(false) }

            ShizukuExpressiveTheme {
                ShizukuLazyScaffold(
                    title = stringResource(R.string.lab_features_title),
                    onNavigateUp = { finish() }
                ) {
                    item {
                        SettingsGroup(title = stringResource(R.string.lab_features_summary)) {
                            SwitchSettingsRow(
                                icon = R.drawable.ic_baseline_link_24,
                                title = stringResource(R.string.shizuku_connectors_title),
                                summary = stringResource(R.string.shizuku_connectors_summary),
                                checked = connectorEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showUnsafeDialog = true
                                    } else {
                                        connectorEnabled = false
                                        ModuleSettings.setConnectorEnabled(false)
                                    }
                                }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = stringResource(R.string.lab_service_behavior_title)) {
                            SwitchSettingsRow(
                                icon = R.drawable.ic_outline_notifications_active_24,
                                title = stringResource(R.string.lab_notify_recovery_title),
                                summary = stringResource(R.string.lab_notify_recovery_summary),
                                checked = notifyRecovery,
                                onCheckedChange = { value ->
                                    notifyRecovery = value
                                    ModuleSettings.setNotifyOnRecovery(value)
                                }
                            )
                            GroupDivider()
                            SwitchSettingsRow(
                                icon = R.drawable.ic_server_restart,
                                title = stringResource(R.string.lab_auto_refresh_title),
                                summary = stringResource(R.string.lab_auto_refresh_summary),
                                checked = autoRefresh,
                                onCheckedChange = { value ->
                                    autoRefresh = value
                                    ModuleSettings.setAutoRefreshOnResume(value)
                                }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = stringResource(R.string.lab_debugging_title)) {
                            SwitchSettingsRow(
                                icon = R.drawable.ic_adb_24dp,
                                title = stringResource(R.string.lab_verbose_logging_title),
                                summary = stringResource(R.string.lab_verbose_logging_summary),
                                checked = verboseLogging,
                                onCheckedChange = { value ->
                                    verboseLogging = value
                                    ModuleSettings.setVerboseLogging(value)
                                }
                            )
                        }
                    }
                }

                if (showUnsafeDialog) {
                    LabWarningDialog(
                        onDismiss = { showUnsafeDialog = false },
                        titleRes = R.string.unsafe_warning_title,
                        messageRes = R.string.unsafe_warning_message,
                        onConfirm = {
                            showUnsafeDialog = false
                            connectorEnabled = true
                            ModuleSettings.setConnectorEnabled(true)
                        }
                    )
                }

            }
        }
    }

    @Composable
    private fun LabWarningDialog(
        onDismiss: () -> Unit,
        titleRes: Int,
        messageRes: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}
