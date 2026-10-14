package com.theemrock.smooth.sensus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theemrock.smooth.sensus.R
import com.theemrock.smooth.sensus.BuildConfig

enum class SmoothDestination { HOME, LEARN_SCENARIO, CHAT, ABOUT, LOCAL_MODEL_TEST }

@Stable
class SmoothNavigationState(initialDestination: SmoothDestination = SmoothDestination.HOME) {
    var destination by mutableStateOf(initialDestination)
        private set

    fun openLearnScenario() {
        destination = SmoothDestination.LEARN_SCENARIO
    }

    fun openChat() {
        destination = SmoothDestination.CHAT
    }

    fun openAbout() { destination = SmoothDestination.ABOUT }
    fun openLocalModel() { destination = SmoothDestination.LOCAL_MODEL_TEST }

    fun backToHome(): Boolean {
        if (destination == SmoothDestination.HOME) return false
        destination = SmoothDestination.HOME
        return true
    }

    companion object {
        val Saver = Saver<SmoothNavigationState, String>(
            save = { it.destination.name },
            restore = { SmoothNavigationState(SmoothDestination.valueOf(it)) }
        )
    }
}

@Composable
fun SmoothApp(
    viewModel: MainViewModel,
    localModelViewModel: LocalModelViewModel,
    chatViewModel: ChatViewModel,
    requestedConversationId: String? = null,
    requestedConversationVersion: Int = 0,
    navigation: SmoothNavigationState = rememberSaveable(saver = SmoothNavigationState.Saver) {
        SmoothNavigationState()
    }
) {
    LaunchedEffect(requestedConversationId, requestedConversationVersion) {
        requestedConversationId?.let { chatViewModel.selectConversation(it); navigation.openChat() }
    }
    BackHandler(enabled = navigation.destination != SmoothDestination.HOME) {
        if (navigation.destination == SmoothDestination.LOCAL_MODEL_TEST) navigation.openChat()
        else navigation.backToHome()
    }

    Surface(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        when (navigation.destination) {
            SmoothDestination.HOME -> SmoothHomeScreen(
                onOpenLearnScenario = navigation::openLearnScenario,
                onOpenChat = navigation::openChat,
                onOpenAbout = navigation::openAbout
            )
            SmoothDestination.LEARN_SCENARIO -> MainAppScreen(
                viewModel = viewModel,
                localModelViewModel = localModelViewModel,
                onExit = navigation::backToHome
            )
            SmoothDestination.CHAT -> ChatRoute(
                viewModel = chatViewModel,
                onBack = navigation::backToHome,
                onOpenLocalModel = navigation::openLocalModel
            )
            SmoothDestination.ABOUT -> AboutScreen(onBack = navigation::backToHome)
            SmoothDestination.LOCAL_MODEL_TEST -> Column(Modifier.fillMaxSize()) {
                TextButton(onClick = navigation::openChat) { Text(stringResource(R.string.back)) }
                LocalModelTestRoute(localModelViewModel)
            }
        }
    }
}

private data class SmoothHomeItem(val title: String, val onClick: () -> Unit)

@Composable
fun SmoothHomeScreen(onOpenLearnScenario: () -> Unit, onOpenChat: () -> Unit, onOpenAbout: () -> Unit) {
    val items = listOf(
        SmoothHomeItem(stringResource(R.string.learn_scenario), onOpenLearnScenario),
        SmoothHomeItem(stringResource(R.string.chat), onOpenChat),
        SmoothHomeItem(stringResource(R.string.about), onOpenAbout)
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = item.onClick),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Sürüm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
        Text(BuildConfig.APPLICATION_ID)
        Text(stringResource(R.string.about_llama_cpp))
    }
}
