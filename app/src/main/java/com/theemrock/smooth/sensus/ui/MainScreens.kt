package com.theemrock.smooth.sensus.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.theemrock.smooth.sensus.model.AppRegistryItem
import com.theemrock.smooth.sensus.model.AppReviewState
import com.theemrock.smooth.sensus.model.AccessMethod
import com.theemrock.smooth.sensus.model.CapabilityRegistryItem
import com.theemrock.smooth.sensus.model.ProvenanceStatus
import com.theemrock.smooth.sensus.BuildConfig
import com.theemrock.smooth.sensus.R

@Composable
private fun reviewFilterLabel(filter: ReviewFilter): String = stringResource(
    when (filter) {
        ReviewFilter.ALL -> R.string.filter_all
        ReviewFilter.UNREVIEWED -> R.string.filter_unreviewed
        ReviewFilter.SELECT -> R.string.filter_select
        ReviewFilter.IGNORE -> R.string.filter_ignore
        ReviewFilter.UNKNOWN -> R.string.filter_unknown
    }
)

@Composable
private fun appTypeFilterLabel(filter: AppTypeFilter): String = stringResource(
    when (filter) {
        AppTypeFilter.ALL -> R.string.filter_all
        AppTypeFilter.USER -> R.string.filter_user
        AppTypeFilter.SYSTEM -> R.string.filter_system
    }
)

@Composable
private fun reviewStateLabel(state: AppReviewState): String = stringResource(
    when (state) {
        AppReviewState.UNREVIEWED -> R.string.filter_unreviewed
        AppReviewState.SELECT -> R.string.filter_select
        AppReviewState.IGNORE -> R.string.filter_ignore
        AppReviewState.UNKNOWN -> R.string.filter_unknown
    }
)

@Composable
private fun capabilityFilterLabel(filter: CapabilityProvenanceFilter): String = stringResource(
    when (filter) {
        CapabilityProvenanceFilter.ALL -> R.string.filter_all
        CapabilityProvenanceFilter.TECHNICAL -> R.string.filter_technical
        CapabilityProvenanceFilter.AI_SUGGESTED -> R.string.filter_ai_suggested
        CapabilityProvenanceFilter.HUMAN_CONFIRMED -> R.string.filter_human_confirmed
        CapabilityProvenanceFilter.VERIFIED -> R.string.filter_verified
        CapabilityProvenanceFilter.UNKNOWN -> R.string.filter_unknown
    }
)

@Composable
private fun accessMethodLabel(method: AccessMethod): String = stringResource(
    when (method) {
        AccessMethod.ANDROID_INTENT -> R.string.access_android_intent
        AccessMethod.DEEP_LINK_URI -> R.string.access_deep_link_uri
        AccessMethod.MEDIA_SESSION -> R.string.access_media_session
        AccessMethod.CONTENT_PROVIDER -> R.string.access_content_provider
        AccessMethod.BOUND_SERVICE -> R.string.access_bound_service
        AccessMethod.EXPORTED_ACTIVITY -> R.string.access_exported_activity
        AccessMethod.OFFICIAL_API -> R.string.access_official_api
        AccessMethod.ACCESSIBILITY -> R.string.access_accessibility
        AccessMethod.UNKNOWN -> R.string.filter_unknown
    }
)

@Composable
private fun provenanceLabel(status: ProvenanceStatus): String = stringResource(
    when (status) {
        ProvenanceStatus.TECHNICALLY_DISCOVERED -> R.string.provenance_technically_discovered
        ProvenanceStatus.AI_SUGGESTED -> R.string.provenance_ai_suggested
        ProvenanceStatus.HUMAN_CONFIRMED -> R.string.provenance_human_confirmed
        ProvenanceStatus.TECHNICALLY_VERIFIED -> R.string.provenance_technically_verified
        ProvenanceStatus.UNKNOWN -> R.string.filter_unknown
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    localModelViewModel: LocalModelViewModel,
    onExit: () -> Unit
) {
    var currentScreen by remember { mutableStateOf("home") }
    val appItems by viewModel.appItems.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val context = LocalContext.current
    var showPasteDialog by remember { mutableStateOf(false) }
    var selectedAppPackage by remember { mutableStateOf<String?>(null) }
    var pendingInventoryJson by remember { mutableStateOf<String?>(null) }
    var pendingInventoryText by remember { mutableStateOf<String?>(null) }
    val saveInventoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingInventoryJson
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(json)
                } ?: error("Unable to open the selected file")
            }.onSuccess {
                Toast.makeText(context, R.string.inventory_saved, Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, R.string.inventory_save_failed, Toast.LENGTH_LONG).show()
            }
        }
        pendingInventoryJson = null
    }
    val saveChatGptTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingInventoryText
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(text)
                } ?: error("Unable to open the selected file")
            }.onSuccess {
                Toast.makeText(context, R.string.inventory_txt_saved, Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, R.string.inventory_txt_save_failed, Toast.LENGTH_LONG).show()
            }
        }
        pendingInventoryText = null
    }
    val importAiJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val success = importAiJsonDocument(
                readText = {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    } ?: error("Unable to open the selected file")
                },
                importJson = viewModel::importAiJson
            )
            if (success) {
                Toast.makeText(context, R.string.ai_json_imported, Toast.LENGTH_SHORT).show()
                currentScreen = "registry"
            } else {
                Toast.makeText(context, R.string.ai_json_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = currentScreen != "home") {
        currentScreen = if (currentScreen == "details") "inventory" else "home"
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = when (currentScreen) {
                            "home" -> stringResource(R.string.learn_scenario)
                            "inventory" -> stringResource(R.string.app_inventory)
                            "registry" -> stringResource(R.string.capability_registry)
                            "details" -> stringResource(R.string.application_details)
                            "local-model-test" -> stringResource(R.string.local_model_test)
                            else -> stringResource(R.string.app_name)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentScreen == "home") {
                            onExit()
                        } else if (currentScreen == "details") {
                            currentScreen = "inventory"
                        } else {
                            currentScreen = "home"
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                when (currentScreen) {
                    "home" -> HomeScreen(
                        appCount = appItems.size,
                        capabilityCount = capabilities.size,
                        buildTimestamp = BuildConfig.BUILD_TIMESTAMP,
                        onScanTap = { viewModel.scanPhone() },
                        onCopyForChatGpt = {
                            pendingInventoryJson = buildInventoryJson(appItems)
                            saveInventoryLauncher.launch(inventoryFilename("json"))
                        },
                        onSaveChatGptTxt = {
                            pendingInventoryText = buildInventoryText(appItems)
                            saveChatGptTextLauncher.launch(inventoryFilename("txt"))
                        },
                        onPasteAiResult = { showPasteDialog = true },
                        onImportAiJson = { importAiJsonLauncher.launch(arrayOf("application/json")) },
                        onOpenInventory = { currentScreen = "inventory" },
                        onOpenRegistry = { currentScreen = "registry" },
                        onOpenLocalModelTest = { currentScreen = "local-model-test" }
                    )
                    "inventory" -> AppInventoryScreen(
                        appItems = appItems,
                        onReviewStateChange = viewModel::updateAppReviewState,
                        onLaunch = { item ->
                            val target = launchTargetFor(item.discoveredApp)
                            val launched = target != null && runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_LAUNCHER)
                                        .setClassName(target.packageName, target.launcherActivity)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.isSuccess
                            if (!launched) {
                                Toast.makeText(context, R.string.app_launch_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAppSelect = { pkg ->
                            selectedAppPackage = pkg
                            currentScreen = "details"
                        }
                    )
                    "details" -> {
                        val appItem = appItems.find { it.packageName == selectedAppPackage }
                        if (appItem != null) {
                            AppDetailsScreen(appItem = appItem)
                        } else {
                            currentScreen = "inventory"
                        }
                    }
                    "registry" -> CapabilityRegistryScreen(
                        capabilities = capabilities,
                        onToggleConfirm = { viewModel.toggleHumanConfirmation(it) }
                    )
                    "local-model-test" -> LocalModelTestRoute(localModelViewModel)
                }
            }
        }
    }

    if (showPasteDialog) {
        PasteAiResultDialog(
            onDismiss = { showPasteDialog = false },
            onImport = { pasteText ->
                val success = viewModel.importAiJson(pasteText)
                if (success) {
                    Toast.makeText(context, R.string.enrichment_succeeded, Toast.LENGTH_LONG).show()
                    showPasteDialog = false
                    currentScreen = "registry"
                } else {
                    Toast.makeText(context, R.string.json_parse_failed, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@Composable
fun HomeScreen(
    appCount: Int,
    capabilityCount: Int,
    buildTimestamp: String,
    onScanTap: () -> Unit,
    onCopyForChatGpt: () -> Unit,
    onSaveChatGptTxt: () -> Unit,
    onPasteAiResult: () -> Unit,
    onImportAiJson: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenRegistry: () -> Unit,
    onOpenLocalModelTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.phase_status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.apps_discovered, appCount), style = MaterialTheme.typography.bodyLarge)
                    Text(text = stringResource(R.string.registry_capabilities, capabilityCount), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        item {
            Button(
                onClick = onScanTap,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(stringResource(R.string.scan_phone))
            }
        }

        item {
            Button(
                onClick = onOpenInventory,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text(stringResource(R.string.open_app_inventory))
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(text = stringResource(R.string.manual_interchange), style = MaterialTheme.typography.labelLarge)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopyForChatGpt,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(stringResource(R.string.save_full_json))
                }

                Button(
                    onClick = onSaveChatGptTxt,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(stringResource(R.string.save_chatgpt_txt))
                }
            }
        }

        item {
            Button(
                onClick = onImportAiJson,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(stringResource(R.string.import_ai_json))
            }
        }

        item {
            Button(
                onClick = onPasteAiResult,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(stringResource(R.string.paste_ai_result))
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            Button(
                onClick = onOpenRegistry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text(stringResource(R.string.open_capability_registry))
            }
        }
        
        item {
            Button(
                onClick = onOpenLocalModelTest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text(stringResource(R.string.open_local_model_test))
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.info))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.manual_interchange_instruction),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.build_time, buildTimestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun importAiJsonDocument(
    readText: () -> String,
    importJson: (String) -> Boolean
): Boolean = runCatching {
    importJson(readText())
}.getOrDefault(false)

@Composable
fun AppInventoryScreen(
    appItems: List<AppRegistryItem>,
    onReviewStateChange: (String, AppReviewState) -> Unit,
    onLaunch: (AppRegistryItem) -> Unit,
    onAppSelect: (String) -> Unit
) {
    if (appItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_apps))
        }
    } else {
        var query by rememberSaveable { mutableStateOf("") }
        var reviewFilter by rememberSaveable { mutableStateOf(ReviewFilter.UNREVIEWED) }
        var appTypeFilter by rememberSaveable { mutableStateOf(AppTypeFilter.ALL) }
        val counts = remember(appItems) { countReviewStates(appItems) }
        val filteredItems = remember(appItems, query, reviewFilter, appTypeFilter) {
            filterAppInventory(appItems, query, reviewFilter, appTypeFilter)
        }
        val populationCount = remember(appItems, appTypeFilter) {
            countAppTypePopulation(appItems, appTypeFilter)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_app)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReviewFilter.entries.forEach { filter ->
                        val count = if (filter == ReviewFilter.ALL) {
                            appItems.size
                        } else {
                            counts[AppReviewState.valueOf(filter.name)] ?: 0
                        }
                        FilterChip(
                            selected = reviewFilter == filter,
                            onClick = { reviewFilter = filter },
                            label = { Text("${reviewFilterLabel(filter)} $count") }
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTypeFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = appTypeFilter == filter,
                            onClick = { appTypeFilter = filter },
                            label = { Text(appTypeFilterLabel(filter)) }
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.showing_count, filteredItems.size, populationCount),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(filteredItems, key = { it.packageName }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = item.discoveredApp.appLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Row {
                                if (launchTargetFor(item.discoveredApp) != null) {
                                    TextButton(onClick = { onLaunch(item) }) {
                                        Text(stringResource(R.string.launch))
                                    }
                                }
                                TextButton(onClick = { onAppSelect(item.packageName) }) {
                                    Text(stringResource(R.string.details))
                                }
                            }
                        }
                        Text(
                            text = item.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = stringResource(if (item.discoveredApp.isSystemApp) R.string.system_app else R.string.user_app),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(if (item.discoveredApp.isEnabled) R.string.enabled else R.string.disabled),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "v${item.discoveredApp.versionName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (item.discoveredApp.isLaunchable) {
                                Text(
                                    text = stringResource(R.string.launchable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.review_state, reviewStateLabel(item.reviewState)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                AppReviewState.SELECT,
                                AppReviewState.IGNORE,
                                AppReviewState.UNKNOWN
                            ).forEach { state ->
                                FilterChip(
                                    selected = item.reviewState == state,
                                    onClick = { onReviewStateChange(item.packageName, state) },
                                    label = { Text(reviewStateLabel(state)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppDetailsScreen(appItem: AppRegistryItem) {
    val app = appItem.discoveredApp
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = app.appLabel, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text = app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = stringResource(R.string.metadata_summary), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.version_value, app.versionName, app.versionCode))
                    Text(stringResource(R.string.enabled_value, stringResource(if (app.isEnabled) R.string.yes else R.string.no)))
                    Text(stringResource(R.string.system_app_value, stringResource(if (app.isSystemApp) R.string.yes else R.string.no)))
                    Text(stringResource(R.string.launchable_value, stringResource(if (app.isLaunchable) R.string.yes else R.string.no)))
                    if (app.isLaunchable) {
                        Text(stringResource(R.string.launcher_activity_value, app.launcherActivity), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (appItem.aiEnrichment != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = stringResource(R.string.ai_enrichment_data), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.category_value, appItem.aiEnrichment.category))
                        Text(stringResource(R.string.suggested_role_value, appItem.aiEnrichment.role))
                    }
                }
            }
        }

        item {
            Text(text = stringResource(R.string.handled_intents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (app.handlesIntents.isEmpty()) {
                Text(stringResource(R.string.none_detected), style = MaterialTheme.typography.bodySmall)
            } else {
                app.handlesIntents.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Text(text = stringResource(R.string.permissions_count, app.relevantPermissions.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val showPerms = app.relevantPermissions.take(8)
            showPerms.forEach {
                Text("• ${it.substringAfterLast(".")}", style = MaterialTheme.typography.bodyMedium)
            }
            if (app.relevantPermissions.size > 8) {
                Text(stringResource(R.string.more_count, app.relevantPermissions.size - 8), style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Text(text = stringResource(R.string.activities_count, app.activities.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            app.activities.take(4).forEach {
                Text("• ${it.substringAfterLast(".")}", style = MaterialTheme.typography.bodySmall)
            }
            if (app.activities.size > 4) {
                Text(stringResource(R.string.more_count, app.activities.size - 4), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun CapabilityRegistryScreen(
    capabilities: List<CapabilityRegistryItem>,
    onToggleConfirm: (CapabilityRegistryItem) -> Unit
) {
    if (capabilities.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.empty_capability_registry), modifier = Modifier.padding(16.dp))
        }
    } else {
        var query by rememberSaveable { mutableStateOf("") }
        var provenanceFilter by rememberSaveable {
            mutableStateOf(CapabilityProvenanceFilter.ALL)
        }
        val filteredCapabilities = remember(capabilities, query, provenanceFilter) {
            filterCapabilityRegistry(capabilities, query, provenanceFilter)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_capabilities)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CapabilityProvenanceFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = provenanceFilter == filter,
                            onClick = { provenanceFilter = filter },
                            label = { Text(capabilityFilterLabel(filter)) }
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.showing_results, filteredCapabilities.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(filteredCapabilities, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (item.provenance) {
                            ProvenanceStatus.TECHNICALLY_DISCOVERED -> MaterialTheme.colorScheme.surfaceVariant
                            ProvenanceStatus.AI_SUGGESTED -> MaterialTheme.colorScheme.tertiaryContainer
                            ProvenanceStatus.HUMAN_CONFIRMED -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.capabilityName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.provider_value, item.appLabel, item.packageName),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            
                            IconButton(onClick = { onToggleConfirm(item) }) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.confirm),
                                    tint = if (item.provenance == ProvenanceStatus.HUMAN_CONFIRMED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = stringResource(R.string.category_value, item.category), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(text = stringResource(R.string.access_method_value, accessMethodLabel(item.possibleAccessMethod)), style = MaterialTheme.typography.bodySmall)
                        Text(text = stringResource(R.string.provenance_value, provenanceLabel(item.provenance)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        if (item.confidence < 1.0) {
                            Text(text = stringResource(R.string.confidence_value, (item.confidence * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                        }
                        if (item.appFeatures.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.features_value, item.appFeatures),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteAiResultDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var textState by remember { mutableStateOf("") }
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = stringResource(R.string.paste_enrichment_json), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            textState = clip.getItemAt(0).text?.toString() ?: ""
                        } else {
                            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.paste_from_clipboard))
                }

                OutlinedTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    label = { Text(stringResource(R.string.enrichment_json_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onImport(textState) }) {
                        Text(stringResource(R.string.import_action))
                    }
                }
            }
        }
    }
}

