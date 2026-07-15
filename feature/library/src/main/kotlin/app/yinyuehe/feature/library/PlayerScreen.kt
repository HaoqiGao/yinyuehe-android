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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.player.PlaybackToggleAction

@Composable
internal fun PlayerScreen(
  state: LibraryUiState,
  bottomPadding: PaddingValues,
  onAction: (MusicBoxAction) -> Unit,
) {
  val playback = state.playback
  val toggleLabel =
    when (playback.toggleAction) {
      PlaybackToggleAction.PLAY -> "播放"
      PlaybackToggleAction.PAUSE -> "暂停"
    }
  val duration = playback.durationMs.coerceAtLeast(0)
  val sliderMaximum = duration.coerceAtLeast(1).toFloat()
  val queueEntries = remember(playback.queueTrackIds) {
    playerQueueEntries(playback.queueTrackIds)
  }
  var seekState by remember(playback.currentTrackId) {
    mutableStateOf(PlayerSeekState(playback.positionMs))
  }
  LaunchedEffect(playback.currentTrackId, playback.positionMs) {
    seekState = seekState.onPlaybackPosition(playback.positionMs)
  }
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
      value = seekState.displayedPositionMs.coerceIn(0, duration).toFloat(),
      onValueChange = { position -> seekState = seekState.onDrag(position.toLong()) },
      onValueChangeFinished = {
        val commit = seekState.finishDrag()
        seekState = commit.state
        commit.positionMs?.let { position -> onAction(MusicBoxAction.SeekTo(position)) }
      },
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
        enabled = playback.canTogglePlayPause,
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .semantics { contentDescription = toggleLabel }
            .testTag("player-toggle"),
      ) {
        Text(toggleLabel)
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
      itemsIndexed(queueEntries, key = { _, entry -> entry.key }) { index, entry ->
        val id = entry.trackId
        val title = state.trackCatalog[id]?.displayTitle ?: id.value
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
                .semantics { contentDescription = "跳转到$title" }
                .testTag("player-queue-jump-$index"),
          ) {
            Text("跳转")
          }
          TextButton(
            onClick = { onAction(MusicBoxAction.RemoveQueueItem(index)) },
            modifier =
              Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .semantics { contentDescription = "从队列移除$title" }
                .testTag("player-queue-remove-$index"),
          ) {
            Text("移除")
          }
        }
      }
    }
  }
}

internal data class PlayerSeekState(
  val displayedPositionMs: Long,
  val isDragging: Boolean = false,
) {
  fun onPlaybackPosition(positionMs: Long): PlayerSeekState =
    if (isDragging) this else copy(displayedPositionMs = positionMs.coerceAtLeast(0))

  fun onDrag(positionMs: Long): PlayerSeekState =
    copy(displayedPositionMs = positionMs.coerceAtLeast(0), isDragging = true)

  fun finishDrag(): PlayerSeekCommit =
    PlayerSeekCommit(
      state = copy(isDragging = false),
      positionMs = displayedPositionMs.takeIf { isDragging },
    )
}

internal data class PlayerSeekCommit(
  val state: PlayerSeekState,
  val positionMs: Long?,
)

internal data class PlayerQueueEntry(
  val trackId: TrackId,
  val key: String,
)

internal fun playerQueueEntries(trackIds: List<TrackId>): List<PlayerQueueEntry> {
  val occurrences = mutableMapOf<TrackId, Int>()
  return trackIds.map { trackId ->
    val occurrence = occurrences.getOrDefault(trackId, 0)
    occurrences[trackId] = occurrence + 1
    PlayerQueueEntry(trackId = trackId, key = "${trackId.value}#$occurrence")
  }
}
