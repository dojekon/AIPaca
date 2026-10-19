package com.aipaca.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.ChatEntity
import com.aipaca.android.ui.bench.BenchScreen
import com.aipaca.android.ui.chat.ChatScreen
import com.aipaca.android.ui.chat.ChatViewModel
import com.aipaca.android.ui.chat.DeleteChatDialog
import com.aipaca.android.ui.chat.RenameChatDialog
import com.aipaca.android.ui.destinations.Destinations
import com.aipaca.android.ui.models.ModelsScreen
import com.aipaca.android.ui.screens.AboutScreen
import com.aipaca.android.ui.screens.DownloadsMenu
import com.aipaca.android.ui.screens.ServerScreen
import com.aipaca.android.ui.screens.SettingsScreen
import com.aipaca.android.ui.search.SearchScreen
import kotlinx.coroutines.launch

private const val DRAWER_CHATS_LIMIT = 20
private val DRAWER_CHAT_MENU_WIDTH = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(container: AppContainer) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destinations.start.route
    val onChat = currentRoute == Destinations.Chat.route || currentRoute == Destinations.CHAT_BY_ID_ROUTE
    val activeChatId = if (onChat) {
        backStackEntry?.arguments?.getLong(Destinations.CHAT_ID_ARG) ?: 0L
    } else {
        null
    }
    val currentTitleRes = Destinations.all.firstOrNull { it.route == currentRoute }?.labelRes
        ?: R.string.app_name

    LaunchedEffect(Unit) {
        scope.launch { container.profileRepository.maybeAutoRefresh() }
    }

    val chats by container.database.chatDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var chatMenuId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<ChatEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatEntity?>(null) }

    fun navigate(destination: Destinations) {
        scope.launch { drawerState.close() }
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openChat(chatId: Long) {
        scope.launch { drawerState.close() }
        if (!onChat || activeChatId != chatId) {
            navController.navigate(Destinations.chatRoute(chatId))
        }
    }

    fun goToNewChat() {
        scope.launch { drawerState.close() }
        navController.navigate(Destinations.Chat.route) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun deleteChat(chat: ChatEntity) {
        scope.launch {
            container.database.messageDao().deleteForChat(chat.id)
            container.database.chatDao().delete(chat)
            if (activeChatId == chat.id) {
                goToNewChat()
            }
        }
    }

    fun renameChat(chat: ChatEntity, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val chatDao = container.database.chatDao()
            chatDao.updateTitle(chat.id, trimmed)
            chatDao.updateTimestamp(chat.id, System.currentTimeMillis())
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Destinations.menuItems.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(destination.labelRes)) },
                            selected = currentRoute == destination.route,
                            onClick = { navigate(destination) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.menu_new_chat)) },
                        selected = activeChatId == 0L,
                        onClick = { openChat(0L) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.drawer_chats_history),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (chats.isEmpty()) {
                        Text(
                            text = stringResource(R.string.drawer_chats_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else {
                        chats.take(DRAWER_CHATS_LIMIT).forEach { chat ->
                            Box(modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)) {
                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            text = chat.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    selected = activeChatId == chat.id,
                                    onClick = { openChat(chat.id) },
                                    modifier = Modifier.padding(end = DRAWER_CHAT_MENU_WIDTH),
                                )
                                IconButton(
                                    onClick = { chatMenuId = chat.id },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.chat_menu),
                                    )
                                }
                                DropdownMenu(
                                    expanded = chatMenuId == chat.id,
                                    onDismissRequest = { chatMenuId = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_rename)) },
                                        onClick = {
                                            chatMenuId = null
                                            renameTarget = chat
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_delete)) },
                                        onClick = {
                                            chatMenuId = null
                                            deleteTarget = chat
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (!onChat) {
                    TopAppBar(
                        title = { Text(stringResource(currentTitleRes)) },
                        navigationIcon = {
                            if (currentRoute == Destinations.Search.route) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.search_back),
                                    )
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        imageVector = Icons.Filled.Menu,
                                        contentDescription = stringResource(R.string.drawer_open),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (currentRoute == Destinations.Models.route) {
                                DownloadsMenu(container)
                            }
                        },
                    )
                }
            },
            contentWindowInsets = if (onChat) {
                WindowInsets(0, 0, 0, 0)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destinations.start.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Destinations.Chat.route) {
                    ChatScreen(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onChatDeleted = { goToNewChat() },
                        viewModel = viewModel(factory = ChatViewModel.factory(0L)),
                    )
                }
                composable(
                    route = Destinations.CHAT_BY_ID_ROUTE,
                    arguments = listOf(
                        navArgument(Destinations.CHAT_ID_ARG) {
                            type = NavType.LongType
                            defaultValue = 0L
                        },
                    ),
                ) { entry ->
                    ChatScreen(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onChatDeleted = { goToNewChat() },
                        viewModel = viewModel(
                            factory = ChatViewModel.factory(
                                entry.arguments?.getLong(Destinations.CHAT_ID_ARG) ?: 0L,
                            ),
                        ),
                    )
                }
                composable(Destinations.Models.route) {
                    ModelsScreen(onSearch = { navigate(Destinations.Search) })
                }
                composable(Destinations.Search.route) { SearchScreen(container) }
                composable(Destinations.Server.route) { ServerScreen(container) }
                composable(Destinations.Bench.route) { BenchScreen() }
                composable(Destinations.Settings.route) { SettingsScreen(container.settingsRepository) }
                composable(Destinations.About.route) { AboutScreen() }
            }
        }
    }

    val renameChatDialogTarget = renameTarget
    if (renameChatDialogTarget != null) {
        RenameChatDialog(
            initialTitle = renameChatDialogTarget.title,
            onConfirm = { title ->
                renameTarget = null
                renameChat(renameChatDialogTarget, title)
            },
            onDismiss = { renameTarget = null },
        )
    }

    val deleteChatDialogTarget = deleteTarget
    if (deleteChatDialogTarget != null) {
        DeleteChatDialog(
            onConfirm = {
                deleteTarget = null
                deleteChat(deleteChatDialogTarget)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
