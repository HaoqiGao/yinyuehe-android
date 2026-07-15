package app.yinyuehe.core.data.playback

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.data.playback.proto.PlaybackRepeatModeProto
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto

internal fun PlaybackSnapshotProto.toReadResult(): PlaybackSnapshotReadResult {
  if (schemaVersion != PlaybackSnapshot.CURRENT_SCHEMA_VERSION) {
    return if (schemaVersion == 0 && serializedSize == 0) {
      PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())
    } else {
      PlaybackSnapshotReadResult.IncompatibleVersion(schemaVersion)
    }
  }

  val rawIds = mediaIdsList
  val repeatMode = repeatModeFromWire()
  if (rawIds.isEmpty()) {
    return PlaybackSnapshotReadResult.Usable(
      PlaybackSnapshot(
        mediaIds = emptyList(),
        currentIndex = -1,
        positionMs = 0,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
      )
    )
  }

  val originalCurrentIndex = currentIndex.coerceIn(rawIds.indices)
  val keptIds =
    rawIds.mapIndexedNotNull { originalIndex, rawId ->
      rawId
        .takeIf(String::isNotBlank)
        ?.let { id -> IndexedTrackId(originalIndex, TrackId(id)) }
    }
  if (keptIds.isEmpty()) {
    return PlaybackSnapshotReadResult.Usable(
      PlaybackSnapshot(
        mediaIds = emptyList(),
        currentIndex = -1,
        positionMs = 0,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
      )
    )
  }

  val currentIdSurvives = rawIds[originalCurrentIndex].isNotBlank()
  val normalizedCurrentIndex =
    if (currentIdSurvives) {
      keptIds.indexOfFirst { item -> item.originalIndex == originalCurrentIndex }
    } else {
      val selected =
        keptIds.firstOrNull { item -> item.originalIndex > originalCurrentIndex }
          ?: keptIds.last { item -> item.originalIndex < originalCurrentIndex }
      keptIds.indexOf(selected)
    }

  return PlaybackSnapshotReadResult.Usable(
    PlaybackSnapshot(
      mediaIds = keptIds.map(IndexedTrackId::trackId),
      currentIndex = normalizedCurrentIndex,
      positionMs = if (currentIdSurvives) positionMs.coerceAtLeast(0) else 0,
      shuffleEnabled = shuffleEnabled,
      repeatMode = repeatMode,
    )
  )
}

internal fun PlaybackSnapshot.toProto(): PlaybackSnapshotProto =
  PlaybackSnapshotProto.newBuilder()
    .setSchemaVersion(schemaVersion)
    .addAllMediaIds(mediaIds.map { id -> id.value })
    .setCurrentIndex(currentIndex)
    .setPositionMs(positionMs)
    .setShuffleEnabled(shuffleEnabled)
    .setRepeatMode(
      when (repeatMode) {
        PlaybackRepeatMode.OFF -> PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_OFF
        PlaybackRepeatMode.ALL -> PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ALL
        PlaybackRepeatMode.ONE -> PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ONE
      }
    )
    .build()

private fun PlaybackSnapshotProto.repeatModeFromWire(): PlaybackRepeatMode =
  when (repeatModeValue) {
    PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ALL.number -> PlaybackRepeatMode.ALL
    PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ONE.number -> PlaybackRepeatMode.ONE
    else -> PlaybackRepeatMode.OFF
  }

private data class IndexedTrackId(
  val originalIndex: Int,
  val trackId: TrackId,
)
