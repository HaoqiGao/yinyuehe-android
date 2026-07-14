package app.yinyuehe.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import java.util.Locale

@Composable
fun LibraryRoute(viewModel: LibraryViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LibraryScreen(state, viewModel::onTrackClick)
}

@Composable
fun LibraryScreen(
  state: LibraryUiState,
  onTrackClick: (TrackId) -> Unit,
) {
  Scaffold { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
          "下午好",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.secondary,
        )
        Text("音悦盒", style = MaterialTheme.typography.headlineLarge)
        Text("随时可播放的本地音乐", style = MaterialTheme.typography.bodyMedium)
      }
      state.playbackError?.let { error ->
        Text(
          text = error.message,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      if (state.isLoading && state.tracks.isEmpty()) {
        Row(
          Modifier.fillMaxWidth().padding(32.dp),
          horizontalArrangement = Arrangement.Center,
        ) {
          CircularProgressIndicator()
        }
      } else {
        LazyColumn(
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(state.tracks, key = { it.id.value }) { track ->
            TrackRow(track, onTrackClick)
          }
        }
      }
    }
  }
}

private val PlaybackError.message: String
  get() =
    when (this) {
      PlaybackError.CONNECTION_FAILED -> "播放器连接失败，请重试"
      PlaybackError.PLAYBACK_FAILED -> "播放失败，请重试"
    }

@Composable
private fun TrackRow(track: Track, onTrackClick: (TrackId) -> Unit) {
  val displayTitle = track.title ?: track.displayName ?: stringResource(R.string.unknown_track)
  val playDescription = stringResource(R.string.play_track_content_description, displayTitle)

  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clickable { onTrackClick(track.id) }
        .semantics { contentDescription = playDescription },
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "◉",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.size(14.dp))
      Column(Modifier.weight(1f)) {
        Text(displayTitle, style = MaterialTheme.typography.titleMedium)
        Text(
          track.artist.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.secondary,
        )
      }
      Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium)
    }
  }
}

private fun formatDuration(durationMs: Long?): String {
  if (durationMs == null) return "--:--"
  val totalSeconds = durationMs / 1_000
  return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
