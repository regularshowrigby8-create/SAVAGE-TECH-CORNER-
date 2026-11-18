package dev.nova.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue // used by `by remember`
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue // used by `var x by remember`
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import dev.nova.tv.data.model.MetaPreview

/**
 * The shell: a focusable side rail and a content area.
 *
 * Every interactive element is a real focus target, because on a TV D-pad a
 * control that cannot take focus does not exist.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaApp(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var route by remember { mutableStateOf(Route.HOME) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SideRail(current = route, onSelect = { route = it })
        Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
            when {
                state.loading -> LoadingPane()
                state.error != null && state.rows.isEmpty() -> ErrorPane(
                    message = state.error ?: "",
                    onRetry = viewModel::refresh
                )
                else -> HomePane(state = state, onTitleClick = viewModel::openTitle)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideRail(current: Route, onSelect: (Route) -> Unit) {
    Column(
        Modifier
            .width(200.dp)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "NOVA",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(start = 24.dp, bottom = 24.dp)
        )
        Route.entries.forEach { entry ->
            RailButton(
                label = entry.label,
                selected = entry == current,
                onClick = { onSelect(entry) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailButton(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePane(state: HomeState, onTitleClick: (MetaPreview) -> Unit) {
    TvLazyColumn(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Header(hero = state.rows.firstOrNull()?.items?.firstOrNull()) }
        items(state.rows) { row ->
            Column {
                Text(
                    row.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(row.items) { title ->
                        PosterCard(title = title, onClick = { onTitleClick(title) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PosterCard(title: MetaPreview, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(154.dp)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            )
                        )
                    )
            ) {
                // Artwork loading is handled by Coil in a later pass; the card
                // renders correctly with the placeholder alone.
                Text(
                    title.name.take(2).uppercase(),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                maxLines = 2
            )
            if (title.rating > 0) {
                Text(
                    "★ %.1f".format(title.rating),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun Header(hero: MetaPreview?) {
    AnimatedVisibility(visible = hero != null) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Text(
                hero?.name ?: "",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            if (!hero?.description.isNullOrBlank()) {
                Text(
                    hero?.description ?: "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 6.dp, end = 240.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            androidx.tv.material3.Surface(onClick = onRetry) {
                Text("Retry", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    }
}

enum class Route(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SERIES("Series"),
    SEARCH("Search"),
    ADDONS("Addons"),
    SETTINGS("Settings")
}
