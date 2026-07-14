package app.yinyuehe.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.yinyuehe.core.common.model.Track

@Composable
internal fun PlaylistsScreen(
  state: LibraryUiState,
  bottomPadding: PaddingValues,
  onAction: (MusicBoxAction) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding.calculateBottomPadding()),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Text("我的歌单", style = MaterialTheme.typography.headlineLarge)
    }
    item {
      CollectionHeader(
        title = "收藏",
        tracks = state.favoriteTracks,
        collection = TrackCollection.FAVORITES,
        playAllTag = "favorites-play-all",
        randomTag = "favorites-play-random",
        onAction = onAction,
      )
    }
    items(state.favoriteTracks, key = { "favorite-${it.id.value}" }) { track ->
      PlaylistTrackRow(
        track = track,
        collection = TrackCollection.FAVORITES,
        favoriteTag = "playlists-favorite-${track.id.value}",
        isFavorite = track.id in state.favoriteTrackIds,
        onAction = onAction,
      )
    }
    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
    item {
      Column(Modifier.testTag("playlists-recent")) {
        CollectionHeader(
          title = "最近播放 · 最多 20 首",
          tracks = state.recentTracks,
          collection = TrackCollection.RECENT,
          playAllTag = "recent-play-all",
          randomTag = "recent-play-random",
          onAction = onAction,
        )
      }
    }
    items(state.recentTracks, key = { "recent-${it.id.value}" }) { track ->
      PlaylistTrackRow(
        track = track,
        collection = TrackCollection.RECENT,
        favoriteTag = "recent-favorite-${track.id.value}",
        isFavorite = track.id in state.favoriteTrackIds,
        onAction = onAction,
      )
    }
  }
}

@Composable
private fun CollectionHeader(
  title: String,
  tracks: List<Track>,
  collection: TrackCollection,
  playAllTag: String,
  randomTag: String,
  onAction: (MusicBoxAction) -> Unit,
) {
  Column {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { onAction(MusicBoxAction.PlayAll(collection)) },
        enabled = tracks.isNotEmpty(),
        modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag(playAllTag),
      ) {
        Text("播放全部")
      }
      Button(
        onClick = { onAction(MusicBoxAction.PlayRandom(collection)) },
        enabled = tracks.isNotEmpty(),
        modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag(randomTag),
      ) {
        Text("随机播放")
      }
    }
  }
}

@Composable
private fun PlaylistTrackRow(
  track: Track,
  collection: TrackCollection,
  favoriteTag: String,
  isFavorite: Boolean,
  onAction: (MusicBoxAction) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(
      onClick = { onAction(MusicBoxAction.PlayTrack(track.id, collection)) },
      modifier = Modifier.weight(1f).sizeIn(minHeight = MinimumTouchTarget),
    ) {
      Column(Modifier.fillMaxWidth()) {
        Text(track.displayTitle)
        Text(track.artist.orEmpty(), style = MaterialTheme.typography.bodySmall)
      }
    }
    TextButton(
      onClick = { onAction(MusicBoxAction.ToggleFavorite(track.id)) },
      modifier =
        Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
          .testTag(favoriteTag),
    ) {
      Text(if (isFavorite) "♥" else "♡")
    }
  }
}
