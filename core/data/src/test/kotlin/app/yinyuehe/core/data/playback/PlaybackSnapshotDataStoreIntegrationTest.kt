package app.yinyuehe.core.data.playback

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackSnapshotDataStoreIntegrationTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun missingFirstInstallFile_readsAsUsableEmptySnapshot() = runTest {
    val file = newSnapshotFile()
    assertFalse(file.exists())
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty()),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun writeThenRecreateDataStore_restoresTheExactSnapshot() = runTest {
    val file = newSnapshotFile()
    val snapshot =
      PlaybackSnapshot(
        mediaIds =
          listOf(
            TrackId("demo:morning-pulse"),
            TrackId("demo:morning-pulse"),
          ),
        currentIndex = 1,
        positionMs = 1_250,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      )
    val first = newHarness(file)
    try {
      first.store.write(snapshot)
    } finally {
      first.close()
    }

    val second = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.Usable(snapshot),
        second.store.read(),
      )
    } finally {
      second.close()
    }
  }

  @Test
  fun corruptedBytes_areReplacedWithTheDefaultEmptySnapshot() = runTest {
    val file = newSnapshotFile()
    file.writeBytes(byteArrayOf(0x0A, 0x02, 0x01))
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty()),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }

    assertEquals(
      PlaybackSnapshotProto.getDefaultInstance(),
      PlaybackSnapshotProto.parseFrom(file.readBytes()),
    )
  }

  @Test
  fun futureVersionRead_doesNotRewriteEvenOneByte() = runTest {
    val file = newSnapshotFile()
    val future =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(99)
        .addMediaIds("local:v1:ZXh0ZXJuYWw:42")
        .setCurrentIndex(0)
        .setPositionMs(9_000)
        .build()
    file.writeBytes(future.toByteArray())
    val bytesBeforeRead = file.readBytes()
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.IncompatibleVersion(99),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }

    assertArrayEquals(bytesBeforeRead, file.readBytes())
  }

  @Test
  fun schemaZeroWithPayload_isIncompatibleAndRemainsByteIdentical() = runTest {
    val file = newSnapshotFile()
    val legacy = PlaybackSnapshotProto.newBuilder().setCurrentIndex(-1).build()
    file.writeBytes(legacy.toByteArray())
    val bytesBeforeRead = file.readBytes()
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.IncompatibleVersion(0),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }

    assertArrayEquals(bytesBeforeRead, file.readBytes())
  }

  @Test
  fun concurrentWholeSnapshotWrites_leaveOneCompleteValidSnapshot() = runTest {
    val file = newSnapshotFile()
    val harness = newHarness(file)
    val snapshots =
      (0 until 20).map { index ->
        PlaybackSnapshot(
          mediaIds = listOf(TrackId("local:v1:ZXh0ZXJuYWw:$index")),
          currentIndex = 0,
          positionMs = index.toLong(),
          shuffleEnabled = index % 2 == 0,
          repeatMode = PlaybackRepeatMode.entries[index % PlaybackRepeatMode.entries.size],
        )
      }
    try {
      coroutineScope {
        snapshots
          .map { snapshot ->
            async(Dispatchers.Default) { harness.store.write(snapshot) }
          }
          .awaitAll()
      }

      val result = harness.store.read() as PlaybackSnapshotReadResult.Usable
      assertTrue(result.snapshot in snapshots)
    } finally {
      harness.close()
    }
    PlaybackSnapshotProto.parseFrom(file.readBytes())
  }

  private fun newSnapshotFile(): File =
    File(temporaryFolder.newFolder(), "playback_snapshot.pb")

  private fun newHarness(file: File): StoreHarness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = createPlaybackSnapshotDataStore(produceFile = { file }, scope = scope)
    return StoreHarness(scope, ProtoPlaybackSnapshotStore(dataStore))
  }
}

private data class StoreHarness(
  val scope: CoroutineScope,
  val store: ProtoPlaybackSnapshotStore,
) {
  suspend fun close() {
    scope.coroutineContext[Job]?.cancelAndJoin()
  }
}
