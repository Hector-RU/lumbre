package com.hector.epubreader.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hector.epubreader.R
import com.hector.epubreader.epub.EpubPublication

@Composable
fun ContentsSheet(publication: EpubPublication, currentChapter: Int, navigate: (Int, String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val chapterIndices = remember(publication) { publication.chapters.mapIndexed { index, chapter -> chapter.path to index }.toMap() }
    val activeIndex = publication.contents.indexOfFirst { chapterIndices[it.path] == currentChapter }
    val filtered = remember(publication, query) {
        publication.contents.withIndex().filter { query.isBlank() || it.value.title.contains(query.trim(), ignoreCase = true) }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex.coerceAtLeast(0))
    LaunchedEffect(query) {
        val target = if (query.isBlank()) activeIndex.coerceAtLeast(0) else 0
        listState.scrollToItem(target)
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.contents), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.chapter, currentChapter + 1, publication.chapters.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(publication.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_contents)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } },
            singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        if (filtered.isEmpty()) Text(stringResource(R.string.no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(filtered, key = { _, entry -> entry.index }) { index, entry ->
                val item = entry.value
                val chapter = chapterIndices[item.path] ?: -1
                val active = entry.index == activeIndex
                val first = index == 0 || item.depth == 0
                val last = index == filtered.lastIndex || filtered[index + 1].value.depth == 0
                Surface(shape = RoundedCornerShape(topStart = if (first) 24.dp else 6.dp, topEnd = if (first) 24.dp else 6.dp,
                    bottomStart = if (last) 24.dp else 6.dp, bottomEnd = if (last) 24.dp else 6.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant, 0.45f),
                    modifier = Modifier.fillMaxWidth().padding(top = if (first && index > 0) 9.dp else 0.dp)
                        .selectable(active, enabled = chapter >= 0, role = Role.Button, onClick = { navigate(chapter, item.fragment) })) {
                    Row(Modifier.padding(start = (16 + item.depth.coerceIn(0, 5) * 12).dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (item.depth == 0) Text((chapter + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        else Icon(Icons.Outlined.SubdirectoryArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            if (active) Text(stringResource(R.string.current_chapter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Icon(if (active) Icons.Outlined.Bookmark else Icons.Outlined.ChevronRight, null, Modifier.size(20.dp),
                            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
