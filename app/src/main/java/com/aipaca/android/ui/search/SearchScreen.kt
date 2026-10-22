package com.aipaca.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.BookmarkEntity
import com.aipaca.android.data.hf.HfGgufFile
import com.aipaca.android.data.hf.HfModelSummary
import com.aipaca.android.data.hf.RamFit
import com.aipaca.android.data.hf.detectTools
import com.aipaca.android.data.hf.quantFromFileName
import com.aipaca.android.data.hf.ramFitFor
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container)),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val query by viewModel.query.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val detailsOf by viewModel.detailsOf.collectAsStateWithLifecycle()
    val loadingDetails by viewModel.loadingDetails.collectAsStateWithLifecycle()
    val detailFiles by viewModel.detailFiles.collectAsStateWithLifecycle()
    val detailsTools by viewModel.detailsTools.collectAsStateWithLifecycle()
    val detailsError by viewModel.detailsError.collectAsStateWithLifecycle()
    val downloadingUrls by viewModel.downloadingUrls.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    val addedMessage = stringResource(R.string.search_added_downloads)
    val openRepoId = detailsOf
    val bookmarkedIds = remember(bookmarks) { bookmarks.mapTo(mutableSetOf()) { it.repoId } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SearchBarRow(
                query = query,
                searching = searching,
                onQueryChange = viewModel::updateQuery,
                onSearch = viewModel::search,
            )
            Spacer(Modifier.height(12.dp))
            when {
                searching -> LoadingState()
                error != null -> Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                openRepoId != null -> RepoDetails(
                    repoId = openRepoId,
                    loading = loadingDetails,
                    files = detailFiles,
                    detailsError = detailsError,
                    toolsSupported = detailsTools,
                    downloadingUrls = downloadingUrls,
                    bookmarked = openRepoId in bookmarkedIds,
                    onToggleBookmark = { viewModel.toggleBookmark(openRepoId) },
                    onBack = viewModel::closeRepo,
                    onDownload = { file ->
                        viewModel.download(file, openRepoId)
                        scope.launch { snackbarHostState.showSnackbar(addedMessage) }
                    },
                )
                results.isEmpty() && (query.isNotBlank() || bookmarks.isEmpty()) -> EmptyState()
                else -> ResultsList(
                    query = query,
                    results = results,
                    bookmarks = bookmarks,
                    bookmarkedIds = bookmarkedIds,
                    onOpenRepo = viewModel::openRepo,
                    onToggleBookmark = viewModel::toggleBookmark,
                )
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSearch, enabled = !searching) {
            Text(stringResource(R.string.search_button))
        }
    }
}

@Composable
private fun ColumnScope.LoadingState() {
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ColumnScope.EmptyState() {
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.search_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.ResultsList(
    query: String,
    results: List<HfModelSummary>,
    bookmarks: List<BookmarkEntity>,
    bookmarkedIds: Set<String>,
    onOpenRepo: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (query.isBlank() && bookmarks.isNotEmpty()) {
            item(key = BOOKMARKS_HEADER_KEY) {
                Text(
                    text = stringResource(R.string.search_bookmarks),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(bookmarks, key = { BOOKMARK_KEY_PREFIX + it.repoId }) { bookmark ->
                BookmarkCard(repoId = bookmark.repoId, onClick = { onOpenRepo(bookmark.repoId) })
            }
        }
        items(results, key = { it.id }) { model ->
            ResultCard(
                model = model,
                bookmarked = model.id in bookmarkedIds,
                onToggleBookmark = { onToggleBookmark(model.id) },
                onClick = { onOpenRepo(model.id) },
            )
        }
    }
}

@Composable
private fun BookmarkCard(repoId: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = repoId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = BookmarkFilledIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ResultCard(
    model: HfModelSummary,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onClick: () -> Unit,
) {
    val tools = detectTools(model.tags, emptyList(), null) == true
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.padding(12.dp).weight(1f),
            ) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.search_downloads, formatCount(model.downloads ?: 0L)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.search_likes, formatCount(model.likes ?: 0L)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tools) {
                        Spacer(Modifier.width(12.dp))
                        Badge(text = stringResource(R.string.search_tools_badge))
                    }
                }
            }
            BookmarkButton(
                bookmarked = bookmarked,
                onClick = onToggleBookmark,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun BookmarkButton(bookmarked: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (bookmarked) BookmarkFilledIcon else BookmarkBorderIcon,
            contentDescription = stringResource(
                if (bookmarked) R.string.search_bookmark_remove else R.string.search_bookmark_add,
            ),
        )
    }
}

@Composable
private fun ColumnScope.RepoDetails(
    repoId: String,
    loading: Boolean,
    files: List<HfGgufFile>,
    detailsError: String?,
    toolsSupported: Boolean?,
    downloadingUrls: Set<String>,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onBack: () -> Unit,
    onDownload: (HfGgufFile) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.search_back),
            )
        }
        Text(
            text = repoId,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (toolsSupported == true) {
            Spacer(Modifier.width(6.dp))
            Badge(text = stringResource(R.string.search_tools_badge))
        }
        BookmarkButton(bookmarked = bookmarked, onClick = onToggleBookmark)
    }
    Spacer(Modifier.height(4.dp))
    when {
        loading -> Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        detailsError != null -> Text(
            text = detailsError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(files, key = { it.fileName }) { file ->
                FileCard(
                    file = file,
                    downloading = file.fileName in downloadingUrls,
                    toolsSupported = toolsSupported,
                    onDownload = { onDownload(file) },
                )
            }
        }
    }
}

@Composable
private fun FileCard(
    file: HfGgufFile,
    downloading: Boolean,
    toolsSupported: Boolean?,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                quantFromFileName(file.fileName)?.let { quant ->
                    Spacer(Modifier.width(6.dp))
                    Badge(text = quant)
                }
                if (toolsSupported == true) {
                    Spacer(Modifier.width(6.dp))
                    Badge(text = stringResource(R.string.search_tools_badge))
                }
                if (npuReady(listOf(file))) {
                    Spacer(Modifier.width(6.dp))
                    Badge(
                        text = stringResource(R.string.search_npu_ready),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RamFitIndicator(fit = ramFitFor(file.sizeBytes))
                Spacer(Modifier.weight(1f))
                Button(onClick = onDownload, enabled = !downloading) {
                    Text(
                        text = stringResource(
                            if (downloading) R.string.search_in_downloads else R.string.search_download,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RamFitIndicator(fit: RamFit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = ramFitColor(fit), shape = CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ramFitLabel(fit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ramFitColor(fit: RamFit): Color = when (fit) {
    RamFit.ENOUGH -> RAM_FIT_ENOUGH
    RamFit.MAYBE -> RAM_FIT_MAYBE
    RamFit.TOO_BIG -> MaterialTheme.colorScheme.error
}

@Composable
private fun ramFitLabel(fit: RamFit): String = stringResource(
    when (fit) {
        RamFit.ENOUGH -> R.string.search_ram_enough
        RamFit.MAYBE -> R.string.search_ram_maybe
        RamFit.TOO_BIG -> R.string.search_ram_too_big
    },
)

@Composable
private fun Badge(
    text: String,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(shape = MaterialTheme.shapes.small, color = color, contentColor = contentColor) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private val RAM_FIT_ENOUGH = Color(0xFF2E7D32)
private val RAM_FIT_MAYBE = Color(0xFFF9A825)

private const val BOOKMARKS_HEADER_KEY = "bookmarks-header"
private const val BOOKMARK_KEY_PREFIX = "bookmark:"

private val BookmarkFilledIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Bookmark") {
        materialPath {
            moveTo(17.0f, 3.0f)
            horizontalLineTo(7.0f)
            curveTo(5.9f, 3.0f, 5.01f, 3.9f, 5.01f, 5.0f)
            lineTo(5.0f, 21.0f)
            lineTo(12.0f, 18.0f)
            lineTo(19.0f, 21.0f)
            verticalLineTo(5.0f)
            curveTo(19.0f, 3.9f, 18.1f, 3.0f, 17.0f, 3.0f)
            close()
        }
    }
}

private val BookmarkBorderIcon: ImageVector by lazy {
    materialIcon(name = "Filled.BookmarkBorder") {
        materialPath {
            moveTo(17.0f, 3.0f)
            horizontalLineTo(7.0f)
            curveTo(5.9f, 3.0f, 5.01f, 3.9f, 5.01f, 5.0f)
            lineTo(5.0f, 21.0f)
            lineTo(12.0f, 18.0f)
            lineTo(19.0f, 21.0f)
            verticalLineTo(5.0f)
            curveTo(19.0f, 3.9f, 18.1f, 3.0f, 17.0f, 3.0f)
            close()
            moveTo(17.0f, 18.0f)
            lineTo(12.0f, 15.82f)
            lineTo(7.0f, 18.0f)
            verticalLineTo(5.0f)
            horizontalLineTo(17.0f)
            verticalLineTo(18.0f)
            close()
        }
    }
}
