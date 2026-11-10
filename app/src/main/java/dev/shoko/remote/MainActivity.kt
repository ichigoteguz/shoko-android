package dev.shoko.remote

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shoko.remote.anidb.*
import dev.shoko.remote.data.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShokoTheme { ShokoApp(model) } }
    }
    override fun onStart() { super.onStart(); model.onForeground(true) }
    override fun onStop() { model.onForeground(false); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShokoApp(model: MainViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val pageError by model.aniDb.pageError.collectAsStateWithLifecycle()
    val browserDialog by model.aniDb.jsDialog.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    val page = state.workspace.page
    val tabs = listOf(Triple("files", "Files", Icons.AutoMirrored.Filled.List), Triple("dump", "AVDump", Icons.Default.CloudUpload),
        Triple("add", "AniDB", Icons.Default.PlaylistAdd), Triple("history", "Activity", Icons.Default.History))
    Scaffold(
        topBar = {
            TopAppBar(title = { Column {
                Text(if (page == "official") "AniDB review" else "Shoko Remote", fontWeight = FontWeight.Bold)
                Text(if (page == "official") "Official AniDB page" else if (state.connected) "Connected · Shoko Server" else "Your library, in reach",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, navigationIcon = { if (page == "official") IconButton(onClick = { model.readOfficialPage() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to editor") } },
                actions = {
                    if (page == "official") TextButton(onClick = { model.readOfficialPage() }) { Text("Use app editor") }
                    else IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Connection settings") }
                })
        },
        bottomBar = {
            if (page != "official" && state.connected) NavigationBar(containerColor = ShokoColors.Navigation) {
                tabs.forEach { (id, title, icon) -> NavigationBarItem(selected = page == id, onClick = { model.navigate(id) },
                    icon = { Icon(icon, title) }, label = { Text(title) }, colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ShokoColors.Primary, selectedTextColor = ShokoColors.Primary,
                        unselectedIconColor = ShokoColors.Text, unselectedTextColor = ShokoColors.Text,
                        indicatorColor = Color.Transparent)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.notice?.let { message -> Notice(message, onDismiss = model::dismissNotice) }
            if (page == "official") {
                pageError?.let { Notice(it, onDismiss = model.aniDb::clearError) }
                OfficialPage(model, Modifier.weight(1f))
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (!state.ready) CircularProgressIndicator(Modifier.padding(48.dp))
                else if (!state.connected) LoginScreen(state, model, Modifier.widthIn(max = 650.dp))
                else when (page) {
                    "dump" -> DumpScreen(state, model)
                    "add" -> MassAddScreen(state, model)
                    "history" -> HistoryScreen(state)
                    else -> FilesScreen(state, model)
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Shoko connection") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.serverUrl.ifBlank { "No server saved" })
        Text("Supports Shoko Server 5.3.3 · app ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        Text("AVDump uses the AniDB username and AVDump key configured on your server.")
        Text("Drafts and templates are saved on this phone. AniDB sign-in stays separate.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } }, dismissButton = {
        if (state.connected) TextButton(onClick = { settings = false; model.logout() }) { Text("Disconnect") }
    })
    browserDialog?.let { dialog ->
        var input by remember(dialog) { mutableStateOf(dialog.defaultValue) }
        AlertDialog(onDismissRequest = { model.aniDb.resolveBrowserDialog(false) }, title = { Text("AniDB") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(dialog.message)
                if (dialog.kind == "prompt") ShokoTextField(input, { input = it }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = { model.aniDb.resolveBrowserDialog(true, input) }) { Text("OK") } },
            dismissButton = { if (dialog.kind != "alert") TextButton(onClick = { model.aniDb.resolveBrowserDialog(false) }) { Text("Cancel") } })
    }
}

@Composable
private fun Notice(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss message") }
        }
    }
}

@Composable
private fun LoginScreen(state: AppState, model: MainViewModel, modifier: Modifier = Modifier) {
    var url by rememberSaveable { mutableStateOf(state.serverUrl) }
    var user by rememberSaveable { mutableStateOf(state.username) }
    var password by remember { mutableStateOf("") }
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Default.VideoLibrary, null, Modifier.padding(22.dp).size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("Welcome to Shoko", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Connect to your Shoko Server to turn unrecognized files into an organized library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard {
            Text("Your home server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ShokoTextField(url, { url = it }, label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8111") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            ShokoTextField(user, { user = it }, label = { Text("Shoko username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ShokoTextField(password, { password = it }, label = { Text("Shoko password") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            ShokoButton(onClick = { model.login(url, user, password); password = "" }, enabled = !state.busy && url.isNotBlank() && user.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) "Connecting…" else "Connect to Shoko")
                Spacer(Modifier.width(12.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
        Text("Keep your phone on the same network as your server, or connect through your VPN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, ShokoColors.Border), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PageHeading(step: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(step.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilesScreen(state: AppState, model: MainViewModel) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    var clearBatch by remember { mutableStateOf(false) }
    val selected = state.workspace.selected
    val locked = state.workspace.draft.animeId > 0
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("01 · Choose files", "Unrecognized files", "Select a release to dump and add to AniDB.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShokoTextField(query, { query = it }, label = { Text("Search filenames") }, singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { model.searchFiles(query) }, enabled = !state.busy) { Icon(Icons.Default.Search, "Search files") }
                IconButton(onClick = { model.refreshFiles(state.pageNumber) }, enabled = !state.busy) { Icon(Icons.Default.Refresh, "Refresh files") }
            }
        }
        item { SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${selected.size} selected", fontWeight = FontWeight.Bold)
                Text("${state.total} unrecognized", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (locked) Text("Your Mass Add draft is saved. Continue it in AniDB, or start a new batch to change files.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShokoButton(onClick = { model.navigate("dump") }, enabled = selected.isNotEmpty()) { Text("Continue"); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 8.dp).size(18.dp)) }
                if (!locked) TextButton(onClick = model::selectVisible) { Text("Select page") }
                if (selected.isNotEmpty()) TextButton(onClick = { clearBatch = true }) { Text("New batch") }
            }
        } }
        if (state.files.isEmpty() && !state.busy) item {
            EmptyCard(Icons.Default.CheckCircle, "All clear here", if (state.query.isBlank()) "No unrecognized files were found on this server." else "Try another filename or clear the search.")
        }
        items(state.files, key = { it.id }) { file ->
            val isSelected = selected.any { it.id == file.id }
            Card(onClick = { if (!locked) model.toggleFile(file) }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, ShokoColors.Border),
                colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isSelected, onCheckedChange = { model.toggleFile(file) }, enabled = !locked)
                    Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(sizeLabel(file.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(file.avDump.status ?: if (file.avDump.lastDumpedAt != null) "Dumped" else "Ready to dump", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.refreshFiles(state.pageNumber - 1) }, enabled = state.pageNumber > 1 && !state.busy) { Text("Previous") }
            Text("Page ${state.pageNumber}", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { model.refreshFiles(state.pageNumber + 1) }, enabled = state.pageNumber * 100 < state.total && !state.busy) { Text("Next") }
        } }
    }
    if (clearBatch) AlertDialog(onDismissRequest = { clearBatch = false }, title = { Text("Start a new batch?") },
        text = { Text("This clears the current selection and Mass Add draft. Your saved templates and activity stay on this phone.") },
        confirmButton = { TextButton(onClick = { clearBatch = false; model.newBatch() }) { Text("Start new batch") } }, dismissButton = { TextButton(onClick = { clearBatch = false }) { Text("Keep draft") } })
}

@Composable
private fun DumpScreen(state: AppState, model: MainViewModel) {
    val selected = state.workspace.selected
    var confirmDump by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<SelectedFile?>(null) }
    val canContinue = selected.isNotEmpty() && selected.all { it.status == "Dumped" || it.recognized }
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("02 · Prepare files", "AVDump", "Your server does the work. You can leave and return while it runs.") }
        if (selected.isEmpty()) item { EmptyCard(Icons.Default.CloudUpload, "Choose your files first", "Open Files and select the release you want to add.") }
        else {
            item { SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${selected.count { it.status == "Dumped" }} / ${selected.size} dumped", fontWeight = FontWeight.Bold)
                    Text(if (state.live) "Live progress" else "Status refresh", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (state.workspace.draft.submission != "Not submitted") Text(state.workspace.draft.submission, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShokoButton(onClick = { confirmDump = true }, enabled = !state.busy && selected.any { it.status in setOf("Not dumped", "Failed") }) { Text("Run AVDump") }
                    ShokoSecondaryButton(onClick = model::recheckDumpStatus, enabled = !state.busy) { Text("Refresh") }
                }
                ShokoButton(onClick = { model.navigate("add") }, enabled = canContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue to AniDB"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
            } }
            items(selected, key = { it.id }) { file -> SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (file.recognized || file.status == "Dumped") Icons.Default.CheckCircle else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(file.name, fontWeight = FontWeight.Medium)
                        Text(if (file.recognized) "Recognized by Shoko" else file.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (file.status == "Running" && file.progress != null) {
                            LinearProgressIndicator(progress = { (file.progress.toFloat() / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${file.progress.roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                        if (file.message.isNotBlank()) Text(file.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (file.status == "Outcome unknown") TextButton(onClick = { retry = file }) { Text("I checked the server; allow retry") }
                    }
                }
            } }
            if (state.workspace.draft.submission != "Not submitted") item {
                ShokoSecondaryButton(onClick = model::submittedAndRecheck, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Queue another recognition check") }
            }
        }
    }
    if (confirmDump) AlertDialog(onDismissRequest = { confirmDump = false }, title = { Text("Dump selected public releases?") },
        text = { Text("AniDB accepts publicly released, unmodified, intact files. Check the selected files before continuing. Your server will send their hashes and media details through AVDump.") },
        confirmButton = { TextButton(onClick = { confirmDump = false; model.startDump() }) { Text("Run AVDump") } }, dismissButton = { TextButton(onClick = { confirmDump = false }) { Text("Cancel") } })
    retry?.let { file -> AlertDialog(onDismissRequest = { retry = null }, title = { Text("Allow an explicit retry?") },
        text = { Text("An interrupted request may have reached the server. Only retry after checking that this file is not still being dumped.") },
        confirmButton = { TextButton(onClick = { model.allowDumpRetry(file.id); retry = null }) { Text("Allow retry") } }, dismissButton = { TextButton(onClick = { retry = null }) { Text("Cancel") } }) }
}

@Composable
private fun MassAddScreen(state: AppState, model: MainViewModel) {
    val draft = state.workspace.draft
    val snapshot = model.snapshot()
    val liveSnapshot by model.aniDb.snapshot.collectAsStateWithLifecycle()
    val error by model.aniDb.pageError.collectAsStateWithLifecycle()
    val query = state.workspace.animeSearchQuery.orEmpty()
    var groupQuery by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var templateTools by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf("") }
    val primaryIds = setOf("addfilem_group", "addfilem_template", "addf_crcstatus", "addfilem_quality", "addfilem_type", "addfilem_ed2k")
    val canUse = liveSnapshot.status in setOf("form", "review") && liveSnapshot.url.contains("/anime/${draft.animeId}/")
    val needsDump = state.workspace.selected.any { it.status != "Dumped" && !it.recognized }
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("03 · Add to AniDB", "Mass Add Files", "The fields you use most, with every other option close by.") }
        if (needsDump) {
            item { SectionCard {
                Text("Finish preparing your files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("All selected files need a successful AVDump before you add them to AniDB.")
                ShokoButton(onClick = { model.navigate("dump") }) { Text("Go to AVDump") }
            } }
        } else if (draft.animeId <= 0) {
            item { SectionCard {
                Text("Choose the anime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Use one anime per batch. Check the title before adding files.", style = MaterialTheme.typography.bodySmall)
                ShokoTextField(query, model::setAnimeQuery, label = { Text("Anime title or AniDB ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ShokoButton(onClick = { model.searchAnime(query) }, enabled = query.isNotBlank() && !state.animeSearching) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Find anime") }
                if (state.animeSearching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Searching…", style = MaterialTheme.typography.bodySmall)
                }
                state.animeSearchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.animeSearchCompleted && state.animeResults.isEmpty()) Text("No matches. Try a different title or AniDB ID.", style = MaterialTheme.typography.bodySmall)
                query.toIntOrNull()?.takeIf { it > 0 }?.let { id -> TextButton(onClick = { model.chooseAnime(id, "AniDB #$id") }) { Text("Use AniDB ID $id directly") } }
            } }
            items(state.animeResults, key = { it.id }) { anime ->
                Card(onClick = { model.chooseAnime(anime.id, anime.title.ifBlank { "AniDB #${anime.id}" }) }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, ShokoColors.Border)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(anime.title, fontWeight = FontWeight.SemiBold); Text("AniDB #${anime.id}", style = MaterialTheme.typography.labelMedium) }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Select anime")
                    }
                }
            }
        } else {
            item { SectionCard {
                Text(draft.animeTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("AniDB #${draft.animeId} · draft saved on this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!canUse) Text("Open AniDB and sign in to load its current form. Your saved edits stay here.", style = MaterialTheme.typography.bodySmall)
                ShokoSecondaryButton(onClick = model::openAniDb) { Icon(Icons.Default.Language, null); Spacer(Modifier.width(8.dp)); Text(if (canUse) "Open official page" else "Open AniDB & sign in") }
                TextButton(onClick = model::changeAnime) { Text("Choose another anime") }
            } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            if (liveSnapshot.status == "unsupported") item { SectionCard { Text("AniDB’s form has changed", fontWeight = FontWeight.Bold); Text(liveSnapshot.message); Text("Use the official page to inspect the new options. No fields will be silently dropped.") } }
            if (snapshot == null) item { EmptyCard(Icons.Default.Language, "Ready for AniDB sign-in", "After signing in, tap Use app editor to load the mobile controls.") }
            else {
                item { SectionCard {
                    Text("Release details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    snapshot.fields.firstOrNull { it.id == "addfilem_group" }?.let { field -> NativeField(field, draft.values[field.id] ?: field.value, enabled = canUse) { model.setField(field.id, it) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShokoTextField(groupQuery, { groupQuery = it }, label = { Text("Search another group") }, singleLine = true, modifier = Modifier.weight(1f))
                        IconButton(onClick = { model.groupSearch(groupQuery) }, enabled = canUse && groupQuery.isNotBlank()) { Icon(Icons.Default.Search, "Search AniDB groups") }
                    }
                    snapshot.fields.firstOrNull { it.id == "addfilem_template" }?.let { field -> NativeField(field, draft.values[field.id] ?: field.value, enabled = canUse, onChange = model::selectBuiltinTemplate) }
                    TextButton(onClick = { templateTools = !templateTools }) { Icon(Icons.Default.Bookmarks, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("My templates") }
                    if (templateTools) TemplateTools(state, model)
                    snapshot.fields.filter { it.id in setOf("addf_crcstatus", "addfilem_quality", "addfilem_type") }.forEach { field -> NativeField(field, draft.values[field.id] ?: field.value) { model.setField(field.id, it) } }
                } }
                if (snapshot.status != "review") item { SectionCard {
                    Text("ED2K links", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Filled from your Shoko selection. You can edit or paste links here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ShokoTextField(draft.values["addfilem_ed2k"].orEmpty(), { model.setField("addfilem_ed2k", it) }, minLines = 4, maxLines = 9,
                        modifier = Modifier.fillMaxWidth(), label = { Text("ED2K links") }, textStyle = MaterialTheme.typography.bodySmall)
                } }
                item { SectionCard {
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                        Text("Advanced · video, audio & subtitles", Modifier.weight(1f)); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (advanced) {
                        val fields = snapshot.fields.filter { it.id !in primaryIds && it.id != "addfilem_group_input" && !it.id.startsWith("template_") }
                        Text("Video flags", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        fields.filter { it.id.startsWith("addstrm_vid_") }.forEach { field -> NativeCheck(field, draft.checks[field.id] ?: field.checked) { model.setCheck(field.id, it) } }
                        TrackControls("Audio tracks", draft.audioTracks ?: snapshot.audioTracks, canUse) { model.changeTrackCount(true, it) }
                        fields.filter { it.id.startsWith("addstrm_aud_") }.forEach { field -> NativeField(field, draft.values[field.id] ?: field.value, enabled = canUse) { model.setField(field.id, it) } }
                        HorizontalDivider()
                        TrackControls("Subtitle tracks", draft.subtitleTracks ?: snapshot.subtitleTracks, canUse) { model.changeTrackCount(false, it) }
                        fields.filter { it.id.startsWith("addstrm_sub_") }.forEach { field ->
                            if (field.kind == "checkbox") NativeCheck(field, draft.checks[field.id] ?: field.checked) { model.setCheck(field.id, it) }
                            else NativeField(field, draft.values[field.id] ?: field.value, enabled = canUse) { model.setField(field.id, it) }
                        }
                    }
                } }
                if (snapshot.rows.isNotEmpty()) {
                    item { SectionCard {
                        Text("Per-file review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        ShokoTextField(date, { date = it }, label = { Text("Release date · yyyy-mm-dd") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TextButton(onClick = { model.applyDate(date) }) { Text("Apply date to all files") }
                    } }
                    items(snapshot.rows, key = { it.index }) { row ->
                        val values = draft.review.find { it.index == row.index } ?: ReviewValue(row.index, row.episode.value, row.version.value, row.releaseDate.value)
                        SectionCard {
                            Text(row.filename, fontWeight = FontWeight.Medium)
                            NativeField(row.episode.copy(label = "Episode"), values.episode) { model.setReview(row.index, episode = it) }
                            NativeField(row.version.copy(label = "Version"), values.version) { model.setReview(row.index, version = it) }
                            ShokoTextField(values.releaseDate, { model.setReview(row.index, date = it) }, label = { Text("Release date") }, placeholder = { Text("yyyy-mm-dd") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item { SectionCard {
                    Text(if (snapshot.status == "review") "Ready for final review" else "Continue to the file review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (snapshot.status == "review") "Your edits will be copied into AniDB’s review. Check everything, then press Add Files yourself."
                        else "Open the populated AniDB page and press Next. Tap Use app editor again to adjust episodes, versions and release dates here.", style = MaterialTheme.typography.bodySmall)
                    ShokoButton(onClick = model::continueOnAniDb, enabled = canUse, modifier = Modifier.fillMaxWidth()) { Text(if (snapshot.status == "review") "Review on AniDB" else "Continue on AniDB"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                } }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun NativeCheck(field: AniDbField, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleableWithLabel(checked, !field.disabled, onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange, enabled = !field.disabled)
        Text(field.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun Modifier.toggleableWithLabel(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) = clickable(enabled = enabled) { onChange(!checked) }

@Composable
private fun NativeField(field: AniDbField, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember(field.id) { mutableStateOf(false) }
    var query by remember(field.id) { mutableStateOf("") }
    val active = enabled && !field.disabled
    if (field.kind == "select") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { open = true; query = "" }, enabled = active, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = ShokoColors.Input, contentColor = ShokoColors.Text), modifier = Modifier.fillMaxWidth()) {
                Text(field.options.find { it.value == value }?.label ?: value.ifBlank { "Choose an option" }, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.UnfoldMore, null, Modifier.size(18.dp))
            }
        }
        if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(field.label) }, text = {
            Column {
                if (field.options.size > 10) ShokoTextField(query, { query = it }, label = { Text("Find an option") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(field.options.filter { query.isBlank() || it.label.contains(query, true) }) { option ->
                        TextButton(onClick = { onChange(option.value); open = false }, enabled = !option.disabled, modifier = Modifier.fillMaxWidth()) {
                            if (option.value == value) Icon(Icons.Default.Check, null, Modifier.padding(end = 10.dp).size(18.dp))
                            Text(option.label, Modifier.weight(1f))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
    } else ShokoTextField(value, onChange, label = { Text(field.label) }, enabled = active, singleLine = field.kind != "multiline", modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TrackControls(label: String, count: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ($count)", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange(count - 1) }, enabled = enabled && count > 0) { Icon(Icons.Default.RemoveCircleOutline, "Remove ${label.lowercase()}") }
        IconButton(onClick = { onChange(count + 1) }, enabled = enabled) { Icon(Icons.Default.AddCircleOutline, "Add ${label.lowercase()}") }
    }
}

@Composable
private fun TemplateTools(state: AppState, model: MainViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved on this phone", style = MaterialTheme.typography.labelLarge)
        Text("Templates save quality, source and track options. Group and file links stay with each batch.", style = MaterialTheme.typography.bodySmall)
        state.templates.forEach { template ->
            Column {
                Text(template.name + if (template.isDefault) " · default" else "", fontWeight = FontWeight.Medium)
                Row {
                    TextButton(onClick = { model.applySavedTemplate(template) }) { Text("Use") }
                    TextButton(onClick = { model.applySavedTemplate(template); name = template.name; editing = template.id }) { Text("Edit") }
                    TextButton(onClick = { model.templateDefault(template.id) }) { Text("Default") }
                    IconButton(onClick = { model.deleteTemplate(template.id) }) { Icon(Icons.Default.DeleteOutline, "Delete ${template.name}") }
                }
            }
        }
        ShokoTextField(name, { name = it }, label = { Text(if (editing == null) "New template name" else "Template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { model.saveTemplate(name, editing); name = ""; editing = null }, enabled = name.isNotBlank()) { Text(if (editing == null) "Save current options" else "Save changes with current options") }
        if (editing != null) TextButton(onClick = { editing = null; name = "" }) { Text("Cancel editing") }
        if (state.templates.isNotEmpty()) TextButton(onClick = { reset = true }) { Text("Reset my templates") }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset saved templates?") }, text = { Text("Remove phone templates and their default selection? AniDB’s built-in templates stay available.") },
        confirmButton = { TextButton(onClick = { model.resetTemplates(); reset = false }) { Text("Reset") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
}

@Composable
private fun OfficialPage(model: MainViewModel, modifier: Modifier) {
    val snapshot by model.aniDb.snapshot.collectAsStateWithLifecycle()
    val loading by model.aniDb.loading.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) { Text(
            when (snapshot.status) {
                "review" -> "Check the file details before pressing AniDB’s Add Files button."
                "form" -> "Your release form. Press Next here, then Use app editor for mobile per-file controls."
                else -> "Sign in on AniDB, then tap Use app editor. No AniDB password is saved by the app."
            }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        if (snapshot.status == "page" && !loading) TextButton(onClick = model::openAniDb, modifier = Modifier.fillMaxWidth()) {
            Text("Open selected anime’s Mass Add form")
        }
        AndroidView(factory = { context ->
            model.aniDb.attachToHost(context)
            (model.aniDb.webView.parent as? ViewGroup)?.removeView(model.aniDb.webView)
            model.aniDb.webView
        }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { model.aniDb.detachFromHost() })
        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.aniDb.refresh() }) { Icon(Icons.Default.Refresh, "Read current AniDB form") }
                ShokoSecondaryButton(onClick = { model.submittedAndRecheck() }, modifier = Modifier.weight(1f)) { Text("I submitted · check Shoko") }
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: AppState) {
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("On this phone", "Activity", "A record of your batch requests and confirmed progress.") }
        if (state.workspace.history.isEmpty()) item { EmptyCard(Icons.Default.History, "A fresh start", "Your AVDump and recognition activity will appear here.") }
        items(state.workspace.history) { entry -> SectionCard {
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, body: String) {
    SectionCard {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MiB".format(bytes / (1024.0 * 1024))
    else -> "$bytes bytes"
}
