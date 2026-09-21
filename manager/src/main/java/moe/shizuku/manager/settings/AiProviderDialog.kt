@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.commandium.AiModelPickerScreen
import moe.shizuku.manager.commandium.AiProviderRepository
import moe.shizuku.manager.commandium.aiProviderPresets
import moe.shizuku.manager.commandium.matchPresetName
import moe.shizuku.manager.commandium.urlIsValid
import moe.shizuku.manager.utils.AiClient
import moe.shizuku.manager.utils.AiExplainUtil

/**
 * Add/edit provider dialog with progressive disclosure: Preset -> Credentials -> Model.
 * The three-stage chip row above the form is a visual tracker, not a multi-page wizard --
 * everything stays one scrollable form so TalkBack focus is never bounced between pages.
 *
 * Models auto-discover when a key is added (new provider) and refresh silently in the
 * background for edited providers once the 24h cache expires; the explicit "Discover
 * models" button remains as a manual force refresh. Discovered lists open through the
 * full-screen AiModelPickerScreen -- a plain dropdown can't render 400+ items.
 */
@Composable
fun AiProviderDialog(
    providerId: String?,
    currentName: String,
    currentBaseUrl: String,
    currentModel: String,
    currentApiKey: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var baseUrl by remember { mutableStateOf(currentBaseUrl) }
    var model by remember { mutableStateOf(currentModel) }
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsUnavailable by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var baseUrlError by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf(matchPresetName(currentName, currentBaseUrl)) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Preload the cached model list when editing a known provider (if any).
    // Brand-new providers auto-discover 500ms after the URL+key settle; edited
    // providers with a stale cache refresh silently in the background while the
    // cached list stays visible. Cache is scoped to base URL, so changing the
    // endpoint naturally yields no stale options. The explicit Discover button
    // remains as an always-available force refresh.
    LaunchedEffect(baseUrl.trim(), apiKey.trim(), providerId) {
        val url = baseUrl.trim()
        if (url.isEmpty() || apiKey.trim().isEmpty()) return@LaunchedEffect
        var cached: List<String> = emptyList()
        var haveCache = false
        if (providerId != null) {
            cached = AiProviderRepository.getCachedModels(providerId, url)
            if (cached.isNotEmpty()) {
                haveCache = true
                modelOptions = cached
            }
        }
        val stale = haveCache && AiProviderRepository.isModelCacheStale(providerId!!, url)
        if (haveCache && !stale) return@LaunchedEffect
        if (stale) {
            // Show the cached list immediately, refresh silently behind it.
            AiClient.listModels(url, apiKey.trim())
                .onSuccess { list ->
                    if (list.isNotEmpty()) {
                        modelOptions = AiProviderRepository.displayModels(url, list)
                        AiProviderRepository.setCachedModels(providerId!!, url, list)
                    }
                }
                .onFailure { }
            return@LaunchedEffect
        }
        // No cache at all: debounce 500ms once URL+key settle, then fetch.
        delay(500)
        if (loadingModels) return@LaunchedEffect
        loadingModels = true
        modelsUnavailable = false
        AiClient.listModels(url, apiKey.trim())
            .onSuccess { list ->
                if (list.isNotEmpty()) {
                    modelOptions = AiProviderRepository.displayModels(url, list)
                    providerId?.let { AiProviderRepository.setCachedModels(it, url, list) }
                } else {
                    modelsUnavailable = true
                }
                loadingModels = false
            }
            .onFailure {
                // Keep whatever cached list we had: a failed refresh must not
                // clobber previously discovered models.
                modelsUnavailable = true
                loadingModels = false
            }
    }

    if (!showModelPicker) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comput_ai_provider_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AiStageIndicator(
                    stage = when {
                        model.isNotBlank() || AiExplainUtil.resolveModel(baseUrl.trim()).isNotBlank() -> 3
                        urlIsValid(baseUrl.trim()) && baseUrl.isNotBlank() ->2
                        else ->1
                    },
                )
                Spacer(Modifier.height(12.dp))
                Box {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.comput_ai_preset_label)) },
                        trailingIcon = {
                            IconButton(onClick = { presetMenuExpanded = !presetMenuExpanded }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.comput_ai_preset_label),
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                        aiProviderPresets.forEach { preset ->
                            val presetLabel = preset.nameRes?.let { stringResource(it) } ?: preset.name
                            DropdownMenuItem(
                                text = { Text(presetLabel) },
                                onClick = {
                                    presetMenuExpanded = false
                                    presetName = presetLabel
                                    name = preset.name
                                    baseUrl = preset.baseUrl
                                    apiKey = ""
                                    model = ""
                                    modelOptions = emptyList()
                                    baseUrlError = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.comput_ai_name_label)) },
                    placeholder = { Text(stringResource(R.string.comput_ai_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        baseUrlError = false
                    },
                    label = { Text(stringResource(R.string.comput_ai_base_url_label)) },
                    placeholder = { Text(stringResource(R.string.comput_ai_base_url_placeholder)) },
                    singleLine = true,
                    isError = baseUrlError,
                    supportingText = if (baseUrlError) {
                        { Text(stringResource(R.string.comput_ai_base_url_invalid)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.comput_api_key_label)) },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Text(
                                text = if (keyVisible) stringResource(R.string.comput_hide_api_key) else stringResource(R.string.comput_show_api_key),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick ={
                            val url = baseUrl.trim()
                            val key = apiKey.trim()
                            if (url.isEmpty() || key.isEmpty()) return@OutlinedButton
                            loadingModels = true
                            modelsUnavailable = false
                            scope.launch {
                                AiClient.listModels(url, key)
                                    .onSuccess { list ->
                                        modelOptions = list
                                        loadingModels = false
                                        providerId?.let { id ->
                                            AiProviderRepository.setCachedModels(id, url, list)
                                        }
                                    }
                                    .onFailure {
                                        // Keep the previously cached list on a failed
                                        // refresh instead of wiping usable options.
                                        modelsUnavailable = true
                                        loadingModels = false
                                    }
                            }
                        },
                        enabled = !loadingModels && baseUrl.trim().isNotEmpty() && apiKey.trim().isNotEmpty(),
                    ) {
                        if (loadingModels) {
                            LoadingIndicator(Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (loadingModels) R.string.comput_ai_discovering else R.string.comput_ai_discover_models))
                    }
                    if (!loadingModels && modelOptions.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.comput_ai_models_found, modelOptions.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.comput_ai_model_label)) },
                    placeholder = { Text(stringResource(if (model.isBlank() && AiExplainUtil.resolveModel(baseUrl.trim()).isNotBlank()) R.string.comput_ai_default_model else R.string.comput_ai_model_placeholder)) },
                    singleLine = true,
                    readOnly = modelOptions.isNotEmpty(),
                    trailingIcon = {
                        if (modelOptions.isNotEmpty()) {
                            IconButton(onClick = { showModelPicker = true }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.comput_ai_pick_model),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (modelsUnavailable) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.comput_ai_models_none),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton ={
            TextButton(
                onClick ={
                    val trimmedUrl = baseUrl.trim()
                    if (trimmedUrl.isNotEmpty() && !urlIsValid(trimmedUrl)) {
                        baseUrlError = true
                    } else {
                        baseUrlError = false
                        onSave(name.trim(), baseUrl.trim(), model.trim(), apiKey.trim())
                    }
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton ={
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
    } else {
        AiModelPickerScreen(
            providerName = name,
            modelOptions = modelOptions,
            currentModel = model,
            defaultModel = AiExplainUtil.resolveModel(baseUrl.trim()),
            onSelect ={
                model = it
                showModelPicker = false
            },
            onDismiss ={ showModelPicker = false },
        )
    }
}

@Composable
private fun AiStageIndicator(stage: Int) {
    val labels = listOf(
        R.string.comput_ai_stage_1,
        R.string.comput_ai_stage_2,
        R.string.comput_ai_stage_3,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, res ->
            val n = index + 1
            val active = n == stage
            val filled = n < stage
            val bg = when {
                active -> MaterialTheme.colorScheme.primaryContainer
                filled -> MaterialTheme.colorScheme.primary.copy(alpha =0.12f)
                else -> Color.Transparent
            }
            val fg = when {
                active || filled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .padding(horizontal =10.dp, vertical =6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$n - " + stringResource(res),
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                    maxLines =1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun computProviderSummary(name: String, model: String): String {
    return when {
        name.isBlank() -> stringResource(R.string.comput_ai_provider_not_configured)
        model.isBlank() -> name
        else -> name + " - " + model
    }
}