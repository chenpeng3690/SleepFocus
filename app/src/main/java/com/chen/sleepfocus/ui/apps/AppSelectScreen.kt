package com.chen.sleepfocus.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.chen.sleepfocus.R
import com.chen.sleepfocus.data.model.AppInfo

/** 图标位图边长（像素）。懒加载时只对可见项做转换，150 个应用约 1MB 内存。 */
private const val ICON_BITMAP_SIZE = 96

/**
 * 应用选择页。
 *
 * 列表由调用方一次性提供全部应用（含系统应用），勾选/搜索/系统应用过滤都在本页内完成，
 * 这样切换过滤条件不需要重新扫描 PackageManager。
 */
@Composable
fun AppSelectScreen(
    apps: List<AppInfo>,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    onToggleApp: (String) -> Unit,
    onSelectionChanged: (Set<String>, Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var showSelectedDialog by remember { mutableStateOf(false) }

    val selectedApps = remember(apps, selectedPackages) {
        apps.filter { it.packageName in selectedPackages }
    }

    val visibleApps = remember(apps, query, showSystemApps) {
        val keyword = query.trim().lowercase()
        apps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (
                    keyword.isEmpty() ||
                        app.label.lowercase().contains(keyword) ||
                        app.packageName.lowercase().contains(keyword)
                    )
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                selectedCount = selectedPackages.size,
                onShowSelected = { showSelectedDialog = true },
                onBack = onBack
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            FilterRow(
                showSystemApps = showSystemApps,
                onShowSystemAppsChange = { showSystemApps = it },
                onSelectVisible = {
                    onSelectionChanged(visibleApps.map { it.packageName }.toSet(), true)
                },
                onClearAll = {
                    onSelectionChanged(selectedPackages, false)
                }
            )

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading && apps.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.apps_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    visibleApps.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.apps_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(items = visibleApps, key = { it.packageName }) { app ->
                                AppRow(
                                    app = app,
                                    selected = app.packageName in selectedPackages,
                                    onToggle = { onToggleApp(app.packageName) }
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }

            SaveBar(
                selectedCount = selectedPackages.size,
                onSave = onSave
            )
        }
    }

    if (showSelectedDialog) {
        SelectedAppsDialog(
            selectedApps = selectedApps,
            selectedCount = selectedPackages.size,
            onDismiss = { showSelectedDialog = false }
        )
    }
}

@Composable
private fun SelectedAppsDialog(
    selectedApps: List<AppInfo>,
    selectedCount: Int,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.apps_selected_dialog_title, selectedCount),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))

                if (selectedApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.apps_selected_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(items = selectedApps, key = { it.packageName }) { app ->
                            SelectedAppRow(app)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedAppRow(app: AppInfo) {
    val icon = remember(app.packageName, app.icon) {
        runCatching {
            app.icon?.toBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE)?.asImageBitmap()
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TopBar(selectedCount: Int, onShowSelected: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_cancel)
            )
        }
        Text(
            text = stringResource(R.string.apps_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onShowSelected) {
            Text(
                text = stringResource(R.string.apps_selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun FilterRow(
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onSelectVisible: () -> Unit,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.apps_show_system),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = showSystemApps, onCheckedChange = onShowSystemAppsChange)
        TextButton(onClick = onSelectVisible) {
            Text(stringResource(R.string.apps_select_all))
        }
        TextButton(onClick = onClearAll) {
            Text(stringResource(R.string.apps_clear_all))
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, selected: Boolean, onToggle: () -> Unit) {
    val icon = remember(app.packageName, app.icon) {
        runCatching {
            app.icon?.toBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE)?.asImageBitmap()
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SaveBar(selectedCount: Int, onSave: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.apps_selected_count, selectedCount),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.apps_self_tip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onSave) {
                Text(stringResource(R.string.apps_save))
            }
        }
    }
}