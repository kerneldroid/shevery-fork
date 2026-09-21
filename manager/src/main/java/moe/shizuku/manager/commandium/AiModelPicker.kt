@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.commandium

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.utils.AiClient
import moe.shizuku.manager.utils.AiExplainUtil

/**
 * Full-screen searchable model picker, used whenever the provider dialog needs to
 * show hundreds of discovered models. A plain dropdown cannot render that many
 * items without jank, so this dedicated screen gives TalkBack a search field and
 * selectable rows with an explicit radio indicator for the current model.
 *
 * The same search/list content is shown inside [AiModelSwitcherSheet] for quick
 * switching straight from the Comput console..
 */
@Composable
fun AiModelPickerScreen(
    providerName: String,
    modelOptions: List<String>,
    currentModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    defaultModel: String = "",
) {
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(onBack = onDismiss)
    val filtered = remember(modelOptions, query) {
        if (query.isBlank()) modelOptions else modelOptions.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    ShizukuLazyScaffold(
        title = stringResource(R.string.comput_ai_pick_model),
        onNavigateUp = onDismiss,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 16.dp),
        actions = {},
    ) {
        item {
            Text(
                text = providerName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal =16.dp, vertical =8.dp),
                maxLines =1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (defaultModel.isNotBlank()) {
            item {
                ModelRow(
                    title = stringResource(R.string.comput_ai_default_model),
                    subtitle = defaultModel,
                    selected = currentModel.isBlank(),
                    onClick = { onSelect("") },
                )
            }
        }
        item {
            ModelSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal =16.dp, vertical =4.dp),
            )
        }
        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical =48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.comput_ai_no_models_match))
                }
            }
        }
        items(filtered, key = { it }) { model ->
            ModelRow(
                title = model,
                subtitle = null,
                selected = model == currentModel,
                onClick = { onSelect(model) },
                titleMaxLines =2,
            )
        }
    }
}

/**
 * Bottom-sheet quick switcher shown straight from the Comput console. Provider
 * chips up top (tap model → pick provider first), then a searchable list of that
 * provider's models. Model lists are auto-discovered once and cached per provider
 * and base-url so reopening the sheet stays instant.
 *
 * Selecting a model updates the active provider + model via [onSelect] —
 * the caller closes the sheet afterward..
 */
@Composable
fun AiModelSwitcherSheet(
    activeProviderId: String?,
    onSelect: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val repo = AiProviderRepository
    val providers = remember { repo.getProviders() }
    var selectedId by remember(activeProviderId, providers) {
        mutableStateOf(activeProviderId ?: providers.firstOrNull()?.id ?: "")
    }
    var models by remember(selectedId) { mutableStateOf(emptyList<String>()) }
    var loading by remember(selectedId) { mutableStateOf(false) }
    var failed by remember(selectedId) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selModel = providers.firstOrNull { it.id == selectedId }?.model ?: ""
    val selProviderBase = providers.firstOrNull { it.id == selectedId }?.baseUrl ?: ""
    val defaultModel = AiExplainUtil.resolveModel(selProviderBase)

    LaunchedEffect(selectedId) {
        if (selectedId.isEmpty()) return@LaunchedEffect
        val provider = providers.firstOrNull { it.id == selectedId } ?: return@LaunchedEffect
        query = ""
        val cached = repo.getCachedModels(provider.id, provider.baseUrl)
        if (cached.isNotEmpty()) {
            models = cached
            loading = false
            failed = false
            // Stale cache: show the cached list instantly, then refresh in the
            // background so renamed/added/removed models surface without the user
            // having to tap anything. A failed refresh silently keeps the cache.
            if (repo.isModelCacheStale(provider.id, provider.baseUrl)) {
                val key = repo.getKey(provider.id)
                if (key.isNotBlank()) {
                    AiClient.listModels(provider.baseUrl, key)
                        .onSuccess { fresh ->
                            if (fresh.isNotEmpty()) {
                                models = repo.displayModels(provider.baseUrl, fresh)
                                repo.setCachedModels(provider.id, provider.baseUrl, fresh)
                            }
                        }
                        .onFailure { }
                }
            }
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val key = repo.getKey(provider.id)
        if (key.isBlank()) {
            models = emptyList()
            loading = false
            failed = true
            return@LaunchedEffect
        }
        val result = AiClient.listModels(provider.baseUrl, key)
        loading = false
        result.onSuccess { list ->
            if (list.isEmpty()) {
                failed = true
            } else {
                models = repo.displayModels(provider.baseUrl, list)
                repo.setCachedModels(provider.id, provider.baseUrl, list)
            }
        }.onFailure {
            failed = true
        }
    }

    val filtered = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min =320.dp)
                .imePadding()
                .padding(bottom =8.dp),
        ) {
            Text(
                text = stringResource(R.string.comput_ai_switch_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal =16.dp, vertical =6.dp),
            )
            if (providers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal =16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { p ->
                        FilterChip(
                            selected = p.id == selectedId,
                            onClick = { selectedId = p.id },
                            label = { Text(p.name, maxLines =1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (providers.isEmpty()) {
                Text(
                    text = stringResource(R.string.comput_ai_empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    text = stringResource(R.string.comput_ai_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal =16.dp),
                )
            } else if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.comput_ai_discovering), style = MaterialTheme.typography.bodySmall)
                }
            } else if (failed) {
                if (defaultModel.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal =16.dp)
                            .selectable(
                                selected = selModel.isBlank(),
                                onClick = { onSelect(selectedId, "") },
                            )
                            .padding(vertical =12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.comput_ai_default_model),
                            modifier = Modifier.weight(1f),
                            maxLines =1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selModel.isBlank()) {
                            RadioButton(selected = true, onClick = null)
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.comput_ai_switch_fail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal =16.dp),
                )
            } else {
                ModelSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal =16.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty() && defaultModel.isBlank()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.comput_ai_no_models_match))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        if (defaultModel.isNotBlank()) {
                            item(key = "default") {
                                ModelRow(
                                    title = stringResource(R.string.comput_ai_default_model),
                                    subtitle = null,
                                    selected = selModel.isBlank(),
                                    onClick = { onSelect(selectedId, "") },
                                    titleMaxLines =1,
                                )
                            }
                        }
                        items(filtered, key = { it }) { model ->
                            ModelRow(
                                title = model,
                                subtitle = null,
                                selected = model == selModel,
                                onClick = { onSelect(selectedId, model) },
                                titleMaxLines =2,
                            )
                        }

                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.comput_ai_search_models)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        modifier = modifier,
    )
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    titleMaxLines: Int = 1,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal =16.dp, vertical =12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines =1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            RadioButton(selected = true, onClick = null)
        }
    }
}
