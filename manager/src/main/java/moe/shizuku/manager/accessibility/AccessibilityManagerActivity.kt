@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.accessibility

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.accessibility.AccessibilityManager as SystemAccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ktx.loge
import moe.shizuku.manager.ui.compose.ExpressiveSwitch
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.SwitchSettingsRow
import rikka.shizuku.Shizuku

/**
 * Accessibility Manager screen.
 *
 * Lists every installed accessibility service with a live enable/disable
 * switch and a pin (keep-alive) toggle. Toggles are written through the
 * Shizuku shell; pinned services are kept alive by [AccessibilityDaemonService].
 */
class AccessibilityManagerActivity : AppActivity() {

    private var tick by mutableIntStateOf(0)

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            tick++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addBinderDeadListener(binderDeadListener)

        setContent {
            val context = LocalContext.current
            val pinnedIds = remember(tick) { AccessibilityKeepAliveStore.getKeepAliveIds() }
            val services = remember(tick) { loadServices(context, pinnedIds) }
            val enabledIds = remember(tick) {
                AccessibilityManager.getEnabledServices(context)
                    .map { it.flattenToString() }
                    .toSet()
            }
            val keepAliveEnabled = remember(tick) { AccessibilityKeepAliveStore.isKeepAliveEnabled() }
            val autoBoot = remember(tick) { AccessibilityKeepAliveStore.isAutoBootEnabled() }
            val shizukuRunning = remember(tick) { Shizuku.pingBinder() }

            var showWriteFailedDialog by rememberSaveable { mutableStateOf(false) }
            var detailsDialogData by remember { mutableStateOf<Pair<String, CharSequence?>?>(null) }

            ShizukuExpressiveTheme {
                ShizukuLazyScaffold(
                    title = stringResource(R.string.accessibility_manager_title),
                    onNavigateUp = { finish() }
                ) {
                    item {
                        SettingsGroup(title = stringResource(R.string.accessibility_manager_keep_alive_group)) {
                            SwitchSettingsRow(
                                icon = R.drawable.ic_system_icon,
                                title = stringResource(R.string.accessibility_manager_keep_alive_title),
                                summary = stringResource(R.string.accessibility_manager_keep_alive_summary),
                                checked = keepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    AccessibilityKeepAliveStore.setKeepAliveEnabled(enabled)
                                    tick++
                                    AccessibilityDaemonService.reconcile(this@AccessibilityManagerActivity)
                                }
                            )
                            GroupDivider()
                            SwitchSettingsRow(
                                icon = R.drawable.ic_server_restart,
                                title = stringResource(R.string.accessibility_manager_auto_boot_title),
                                summary = stringResource(R.string.accessibility_manager_auto_boot_summary),
                                checked = autoBoot,
                                enabled = keepAliveEnabled,
                                onCheckedChange = { enabled ->
                                    AccessibilityKeepAliveStore.setAutoBootEnabled(enabled)
                                    tick++
                                }
                            )
                        }
                    }

                    item {
                        if (!shizukuRunning) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.accessibility_manager_shizuku_required),
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    item {
                        SettingsGroup(
                            title = stringResource(
                                R.string.accessibility_manager_services_group,
                                services.size
                            )
                        ) {
                            if (services.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.accessibility_manager_services_empty),
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                services.forEachIndexed { index, service ->
                                    ServiceRow(
                                        service = service,
                                        enabled = enabledIds.contains(service.id),
                                        pinned = pinnedIds.contains(service.id),
                                        canWrite = shizukuRunning,
                                        onToggle = { target ->
                                            // Unpin a service when the user disables it, so the
                                            // keep-alive daemon doesn't immediately re-enable it
                                            // (toggle "fight"). Mirrors the reference app's behavior.
                                            if (!target && AccessibilityKeepAliveStore.isPinned(service.id)) {
                                                AccessibilityKeepAliveStore.removePinned(service.id)
                                            }
                                            val ok = if (target) {
                                                AccessibilityManager.enableService(context, service.id)
                                            } else {
                                                AccessibilityManager.disableService(context, service.id)
                                            }
                                            if (!ok) {
                                                showWriteFailedDialog = true
                                            }
                                            tick++
                                         },
                                         onPin = {
                                             if (AccessibilityKeepAliveStore.isPinned(service.id)) {
                                                 AccessibilityKeepAliveStore.removePinned(service.id)
                                             } else {
                                                 AccessibilityKeepAliveStore.addPinned(service.id)
                                             }
                                             tick++
                                         },
                                         onDetails = {
                                             detailsDialogData = Pair(service.label, service.description)
                                         }
                                     )
                                     if (index < services.lastIndex) {
                                         GroupDivider()
                                     }
                                 }
                             }
                         }
                     }
                 }

                 if (showWriteFailedDialog) {
                     AlertDialog(
                         onDismissRequest = { showWriteFailedDialog = false },
                         text = {
                             Text(
                                 text = stringResource(R.string.accessibility_manager_write_failed),
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                         },
                         confirmButton = {
                             Button(onClick = { showWriteFailedDialog = false }) {
                                 Text(stringResource(android.R.string.ok))
                             }
                         },
                         containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                         shape = MaterialTheme.shapes.extraLarge
                     )
                 }

                 detailsDialogData?.let { (title, description) ->
                     AlertDialog(
                         onDismissRequest = { detailsDialogData = null },
                         title = {
                             Text(
                                 text = title,
                                 style = MaterialTheme.typography.headlineSmall,
                                 fontWeight = FontWeight.Bold
                             )
                         },
                         text = if (!description.isNullOrBlank()) {
                             {
                                 Text(
                                     text = description.toString(),
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                 )
                             }
                         } else null,
                         confirmButton = {
                             Button(onClick = { detailsDialogData = null }) {
                                 Text(stringResource(android.R.string.ok))
                             }
                         },
                         containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                         shape = MaterialTheme.shapes.extraLarge
                     )
                 }
             }
         }
    }

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun loadServices(context: Context, pinnedIds: Set<String>): List<AccessibilityServiceEntry> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as SystemAccessibilityManager?
            ?: return emptyList()
        val pm = context.packageManager
        return am.getInstalledAccessibilityServiceList().mapNotNull { info ->
            try {
                val componentName = info.resolveInfo.serviceInfo.run {
                    android.content.ComponentName(packageName, name)
                }
                val appInfo = pm.getApplicationInfo(componentName.packageName, 0)
                val label = info.resolveInfo.loadLabel(pm)?.toString()
                    ?: pm.getApplicationLabel(appInfo).toString()
                val description = info.loadDescription(pm)?.toString()
                    ?: context.getString(R.string.accessibility_manager_service_no_description)
                val icon = pm.getApplicationIcon(componentName.packageName)
                    .toBitmap(64, 64)
                    .asImageBitmap()
                AccessibilityServiceEntry(
                    id = componentName.flattenToString(),
                    label = label,
                    description = description,
                    icon = icon
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: Exception) {
                loge("Failed to load accessibility service", e)
                null
            }
        }.sortedWith(
            compareBy<AccessibilityServiceEntry> { it.id !in pinnedIds }
                .thenBy { it.label.lowercase() }
        )
    }
}

private data class AccessibilityServiceEntry(
    val id: String,
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap
)

@Composable
private fun ServiceRow(
    service: AccessibilityServiceEntry,
    enabled: Boolean,
    pinned: Boolean,
    canWrite: Boolean,
    onToggle: (Boolean) -> Unit,
    onPin: () -> Unit,
    onDetails: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = true, onClick = onDetails)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = service.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = service.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = service.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (enabled) {
            IconButton(onClick = onPin) {
                Icon(
                    imageVector = if (pinned) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = stringResource(
                        if (pinned) {
                            R.string.accessibility_manager_unpin
                        } else {
                            R.string.accessibility_manager_pin
                        }
                    ),
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        ExpressiveSwitch(
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = canWrite
        )
    }
}
