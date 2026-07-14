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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerScreen(
  state: LibraryUiState,
  bottomPadding: PaddingValues,
  onAction: (MusicBoxAction) -> Unit,
) {
  val playback = state.playback
  val duration = playback.durationMs.coerceAtLeast(0)
  val sliderMaximum = duration.coerceAtLeast(1).toFloat()
  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(bottom = bottomPadding.calculateBottomPadding())
        .padding(horizontal = 20.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("正在播放", style = MaterialTheme.typography.labelLarge)
    Text(
      state.currentTrack?.displayTitle ?: "尚未选择音乐",
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(state.currentTrack?.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium)
    Slider(
      value = playback.positionMs.coerceIn(0, duration.coerceAtLeast(0)).toFloat(),
      onValueChange = { onAction(MusicBoxAction.SeekTo(it.toLong())) },
      valueRange = 0f..sliderMaximum,
      enabled = playback.canSeek && duration > 0,
      modifier = Modifier.fillMaxWidth().testTag("player-seek"),
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(
        onClick = { onAction(MusicBoxAction.Previous) },
        enabled = playback.canPrevious,
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .testTag("player-previous"),
      ) {
        Text("上一首")
      }
      Button(
        onClick = { onAction(MusicBoxAction.TogglePlayPause) },
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .testTag("player-toggle"),
      ) {
        Text(if (playback.isPlaying) "暂停" else "播放")
      }
      Button(
        onClick = { onAction(MusicBoxAction.Next) },
        enabled = playback.canNext,
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .testTag("player-next"),
      ) {
        Text("下一首")
      }
    }
    Text(
      "播放队列 · ${playback.queueTrackIds.size}",
      modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
      style = MaterialTheme.typography.titleMedium,
    )
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("player-queue"),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      itemsIndexed(
        playback.queueTrackIds,
        key = { index, id -> "${id.value}#$index" },
      ) { index, id ->
        val title = state.allKnownTracks.firstOrNull { it.id == id }?.displayTitle ?: id.value
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (index == playback.currentIndex) "▶ $title" else title,
            modifier = Modifier.weight(1f),
          )
          TextButton(
            onClick = { onAction(MusicBoxAction.JumpToQueueItem(index)) },
            modifier =
              Modifier.sizeIn(minHeight = MinimumTouchTarget)
                .testTag("player-queue-jump-$index"),
          ) {
            Text("跳转")
          }
          TextButton(
            onClick = { onAction(MusicBoxAction.RemoveQueueItem(index)) },
            modifier =
              Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .testTag("player-queue-remove-$index"),
          ) {
            Text("移除")
          }
        }
      }
    }
  }
}
