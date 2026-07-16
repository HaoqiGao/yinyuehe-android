package app.yinyuehe.core.data.playback

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.data.playback.proto.PlaybackRepeatModeProto
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSnapshotProtoMapperTest {
  @Test
  fun zeroByteDefaultInstance_isFirstInstallEmptySnapshot() {
    assertEquals(
      PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty()),
      PlaybackSnapshotProto.getDefaultInstance().toReadResult(),
    )
  }

  @Test
  fun schemaZeroWithPayload_isIncompatibleAndNotGuessed() {
    val proto = PlaybackSnapshotProto.newBuilder().setCurrentIndex(-1).build()

    assertEquals(
      PlaybackSnapshotReadResult.IncompatibleVersion(0),
      proto.toReadResult(),
    )
  }

  @Test
  fun futureAndNegativeVersions_areIncompatible() {
    val future = PlaybackSnapshotProto.newBuilder().setSchemaVersion(2).build()
    val negative = PlaybackSnapshotProto.newBuilder().setSchemaVersion(-1).build()

    assertEquals(
      PlaybackSnapshotReadResult.IncompatibleVersion(2),
      future.toReadResult(),
    )
    assertEquals(
      PlaybackSnapshotReadResult.IncompatibleVersion(-1),
      negative.toReadResult(),
    )
  }

  @Test
  fun blankCurrentId_selectsSuccessorAndResetsPosition() {
    val proto =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(1)
        .addAllMediaIds(listOf("local:v1:ZXh0ZXJuYWw:1", " ", "demo:city-walk"))
        .setCurrentIndex(1)
        .setPositionMs(777)
        .setShuffleEnabled(true)
        .setRepeatMode(PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ALL)
        .build()

    assertEquals(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(
          mediaIds =
            listOf(
              TrackId("local:v1:ZXh0ZXJuYWw:1"),
              TrackId("demo:city-walk"),
            ),
          currentIndex = 1,
          positionMs = 0,
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.ALL,
        )
      ),
      proto.toReadResult(),
    )
  }

  @Test
  fun survivingCurrentId_reindexesAndPreservesNonNegativePosition() {
    val proto =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(1)
        .addAllMediaIds(listOf(" ", "demo:city-walk", "demo:night-drive"))
        .setCurrentIndex(2)
        .setPositionMs(444)
        .build()

    assertEquals(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(
          mediaIds = listOf(TrackId("demo:city-walk"), TrackId("demo:night-drive")),
          currentIndex = 1,
          positionMs = 444,
        )
      ),
      proto.toReadResult(),
    )
  }

  @Test
  fun invalidNumbersAreNormalizedWithoutLosingModes() {
    val proto =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(1)
        .addMediaIds("demo:city-walk")
        .setCurrentIndex(Int.MAX_VALUE)
        .setPositionMs(-20)
        .setShuffleEnabled(true)
        .setRepeatModeValue(99)
        .build()

    assertEquals(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(
          mediaIds = listOf(TrackId("demo:city-walk")),
          currentIndex = 0,
          positionMs = 0,
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.OFF,
        )
      ),
      proto.toReadResult(),
    )
  }

  @Test
  fun domainRoundTrip_preservesDuplicatesAndModes() {
    val id = TrackId("demo:soft-echo")
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(id, id),
        currentIndex = 1,
        positionMs = 300,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
      )

    assertEquals(
      PlaybackSnapshotReadResult.Usable(snapshot),
      snapshot.toProto().toReadResult(),
    )
  }
}
