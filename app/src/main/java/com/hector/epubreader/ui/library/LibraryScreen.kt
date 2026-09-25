package com.hector.epubreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hector.epubreader.R
import com.hector.epubreader.data.local.BookEntity
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.ui.LibraryUiState
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(state: LibraryUiState, preferences: ReaderPreferences, search: Boolean, add: () -> Unit, open: (String) -> Unit, remove: (BookEntity) -> Unit, reset: (BookEntity, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<BookEntity?>(null) }
    var info by remember { mutableStateOf<BookEntity?>(null) }
    val locale = LocalContext.current.resources.configuration.locales[0]
    val books = remember(state.books, query, preferences.sort, preferences.ascending) {
        val list = state.books.filter { it.title.contains(query, true) || it.author.orEmpty().contains(query, true) }
        val sorted = when (preferences.sort) {
            "title" -> list.sortedBy { it.title.lowercase() }
            "author" -> list.sortedBy { it.author.orEmpty().lowercase() }
            "added" -> list.sortedBy { it.dateAdded }
            "progress" -> list.sortedBy { it.progress }
            else -> list.sortedWith(compareBy<BookEntity> { it.lastRead }.thenBy { it.dateAdded })
        }
        if (preferences.ascending) sorted else sorted.reversed()
    }
    Column(Modifier.fillMaxSize()) {
        if (search) OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search_library)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        if (state.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.importing), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            books.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(if (state.books.isEmpty()) R.string.empty_title else R.string.no_results), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(if (state.books.isEmpty()) R.string.empty_description else R.string.search_hint), style = MaterialTheme.typography.bodyLarge)
                if (state.books.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = add, enabled = !state.importing) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_book)) }
                }
            }
            else -> {
                Text(pluralStringResource(R.plurals.books_count, books.size, books.size), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preferences.listView) LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(books, key = { it.id }) { book ->
                        Row(Modifier.fillMaxWidth().clickable { open(book.id) }, verticalAlignment = Alignment.CenterVertically) {
                            BookCover(book, Modifier.width(64.dp).height(92.dp))
                            Column(Modifier.weight(1f).padding(16.dp)) { BookText(book) }
                            BookMenu(book, open, { info = book }, { deleting = book }, reset)
                        }
                    }
                } else LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 96.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(books, key = { it.id }) { book ->
                        Column {
                            Column(Modifier.clickable { open(book.id) }) {
                                BookCover(book, Modifier.fillMaxWidth().aspectRatio(0.68f))
                                Spacer(Modifier.height(10.dp))
                                BookText(book)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.weight(1f).height(3.dp))
                                BookMenu(book, open, { info = book }, { deleting = book }, reset)
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { book -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.delete_description)) }, confirmButton = { TextButton(onClick = { remove(book); deleting = null }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
    info?.let { book -> AlertDialog(onDismissRequest = { info = null }, title = { Text(book.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(book.author ?: stringResource(R.string.unknown_author))
            Text(stringResource(R.string.book_details, book.chapterCount, (book.progress * 100).toInt(), DateFormat.getDateInstance(DateFormat.DEFAULT, locale).format(Date(book.dateAdded))))
            book.language?.let { Text(stringResource(R.string.language, it)) }
            book.publisher?.let { Text(stringResource(R.string.publisher, it)) }
            Text(stringResource(R.string.origin, book.sourceUri), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }, confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.close)) } }) }
}

@Composable
private fun BookCover(book: BookEntity, modifier: Modifier) {
    Surface(modifier.clip(RoundedCornerShape(8.dp)), color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(book.title.take(1).uppercase(), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (book.coverPath != null) AsyncImage(model = File(book.coverPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun BookText(book: BookEntity) {
    Text(book.title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(book.author ?: stringResource(R.string.unknown_author), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(stringResource(R.string.progress_percent, (book.progress * 100).toInt()), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BookMenu(book: BookEntity, open: (String) -> Unit, info: () -> Unit, delete: () -> Unit, reset: (BookEntity, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.book_options, book.title)) }
        DropdownMenu(expanded, { expanded = false }) {
            listOf<Pair<Int, () -> Unit>>(R.string.open_book to { open(book.id) }, R.string.book_info to info, R.string.mark_read to { reset(book, true) }, R.string.reset_progress to { reset(book, false) }, R.string.remove_library to delete).forEach { (label, action) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; action() })
            }
        }
    }
}
