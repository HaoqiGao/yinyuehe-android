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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import java.util.Locale

@Composable
internal fun HomeScreen(
  state: LibraryUiState,
  bottomPadding: PaddingValues,
  onAction: (MusicBoxAction) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding.calculateBottomPadding()),
  ) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
      Text("音悦盒", style = MaterialTheme.typography.headlineLarge)
      Text(
        if (state.librarySource == LibrarySource.LOCAL) "本地曲库" else "示例曲库",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { onAction(MusicBoxAction.PlayAll(TrackCollection.LIBRARY)) },
          enabled = state.libraryTracks.isNotEmpty(),
          modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag("home-play-all"),
        ) {
          Text("播放全部")
        }
        Button(
          onClick = { onAction(MusicBoxAction.PlayRandom(TrackCollection.LIBRARY)) },
          enabled = state.libraryTracks.isNotEmpty(),
          modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag("home-play-random"),
        ) {
          Text("随机播放")
        }
      }
      if (state.hasAudioPermission) {
        TextButton(
          onClick = { onAction(MusicBoxAction.Rescan) },
          enabled = !state.isScanning,
          modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag("home-rescan"),
        ) {
          if (state.isScanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
          }
          Text(if (state.isScanning) "扫描中" else "重新扫描本地音乐")
        }
      } else {
        TextButton(
          onClick = { onAction(MusicBoxAction.RequestAudioPermission) },
          enabled = !state.permissionRequestPending,
          modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag("home-request-permission"),
        ) {
          Text(if (state.permissionRequestPending) "等待授权结果" else "授权并扫描本地音乐")
        }
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("home-track-list"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(state.libraryTracks, key = { it.id.value }) { track ->
        HomeTrackRow(
          track = track,
          isFavorite = track.id in state.favoriteTrackIds,
          onAction = onAction,
        )
      }
    }
  }
}

@Composable
private fun HomeTrackRow(
  track: Track,
  isFavorite: Boolean,
  onAction: (MusicBoxAction) -> Unit,
) {
  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clickable { onAction(MusicBoxAction.PlayTrack(track.id, TrackCollection.LIBRARY)) }
        .testTag("home-play-${track.id.value}"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("◉", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.size(12.dp))
      Column(Modifier.weight(1f)) {
        Text(track.displayTitle, style = MaterialTheme.typography.titleMedium)
        Text(
          track.artist.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.secondary,
        )
        Text(track.durationLabel, style = MaterialTheme.typography.labelSmall)
      }
      TextButton(
        onClick = { onAction(MusicBoxAction.AddToQueue(track.id)) },
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .semantics { contentDescription = "将${track.displayTitle}加入播放队列" }
            .testTag("home-add-queue-${track.id.value}"),
      ) {
        Text("+")
      }
      TextButton(
        onClick = { onAction(MusicBoxAction.ToggleFavorite(track.id)) },
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .semantics {
              contentDescription =
                if (isFavorite) "取消收藏${track.displayTitle}" else "收藏${track.displayTitle}"
            }
            .testTag("home-favorite-${track.id.value}"),
      ) {
        Text(if (isFavorite) "♥" else "♡")
      }
    }
  }
}

internal val Track.displayTitle: String
  get() = title ?: displayName ?: "未知曲目"

internal val Track.durationLabel: String
  get() {
    val totalSeconds = (durationMs ?: return "--:--") / 1_000
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
  }

internal val MinimumTouchTarget: Dp = 48.dp
