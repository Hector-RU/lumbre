package com.hector.epubreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import com.hector.epubreader.R
import com.hector.epubreader.ReaderApplication
import com.hector.epubreader.ui.library.LibraryScreen
import com.hector.epubreader.ui.reader.*
import com.hector.epubreader.ui.settings.SettingsScreen
import com.hector.epubreader.ui.theme.EpubTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(vm: AppViewModel = viewModel()) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val preferencesLoaded by vm.preferencesLoaded.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApplication
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "library"
    val reader = route.startsWith("reader/")
    val readerSettings = route == "readerSettings"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val add = { launcher.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip")) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(library.message) {
        library.message?.let { snackbar.showSnackbar(context.getString(it)); vm.clearMessage() }
    }
    if (!preferencesLoaded) return
    EpubTheme(preferences.appTheme, preferences.dynamicColors, preferences.interfaceColor) {
        val destinations = listOf(Triple("library", R.string.library, Icons.AutoMirrored.Outlined.MenuBook), Triple("search", R.string.search, Icons.Outlined.Search), Triple("settings", R.string.settings, Icons.Outlined.Settings))
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!reader) TopAppBar(title = { Text(stringResource(destinations.firstOrNull { it.first == route }?.second ?: R.string.settings)) }, navigationIcon = {
                    if (readerSettings) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                })
            }, bottomBar = {
                if (!reader && !readerSettings) NavigationBar {
                    destinations.forEach { (destination, label, icon) ->
                        NavigationBarItem(selected = route == destination, onClick = { nav.navigate(destination) { popUpTo("library") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
                    }
                }
            }, floatingActionButton = {
                if (route == "library" && library.books.isNotEmpty() && !library.importing) FloatingActionButton(onClick = add) { Icon(Icons.Outlined.Add, stringResource(R.string.add_book)) }
            }) { insets ->
            NavHost(nav, startDestination = "library", modifier = Modifier.padding(insets).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                listOf("library", "search").forEach { destination -> composable(destination) {
                    LibraryScreen(library, preferences, destination == "search", add, { nav.navigate("reader/$it") }, vm::remove, vm::reset)
                } }
                listOf("settings", "readerSettings").forEach { destination -> composable(destination) { SettingsScreen(preferences, { vm.updatePreferences(it) }, { vm.clearHistory() }) } }
                composable("reader/{bookId}") { backStack ->
                    val id = requireNotNull(backStack.arguments?.getString("bookId"))
                    val readerVm: ReaderViewModel = viewModel(key = id, factory = viewModelFactory { initializer { ReaderViewModel(app, id) } })
                    ReaderScreen(readerVm, preferences, { vm.updatePreferences(it) }, { nav.popBackStack() }, { nav.navigate("readerSettings") })
                }
            }
        }
    }
}
