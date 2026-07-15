package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTrackRepositoryTest {
  @Test
  fun initialTracks_inferDemoOnlyForANonEmptyAllDemoList() = runTest {
    val demos = listOf(track("demo:one", isDemo = true))
    val demoRepository = FakeTrackRepository(demos)
    val emptyRepository = FakeTrackRepository(emptyList())

    assertEquals(LibrarySource.DEMO, demoRepository.observeLibrary().first().source)
    assertEquals(demos, demoRepository.demoTracks())
    assertTrue(demoRepository.observeAvailableLocalTracks().first().isEmpty())
    assertEquals(emptyList<Track>(), emptyRepository.demoTracks())
    assertTrue(emptyRepository.observeAvailableLocalTracks().first().isEmpty())
  }

  @Test
  fun initialLocalTracks_areExposedThroughCompatibilityObservation() = runTest {
    val locals = listOf(track("local:one", isDemo = false))
    val repository = FakeTrackRepository(locals)

    assertEquals(LibrarySource.LOCAL, repository.observeLibrary().first().source)
    assertEquals(locals, repository.observeAvailableLocalTracks().first())
    assertEquals(locals, repository.observeTracks().first())
    assertTrue(repository.demoTracks().isEmpty())
  }

  @Test
  fun setTracks_switchesTheLegacySourceWithoutRetainingTheInactiveValues() = runTest {
    val locals = listOf(track("local:one", isDemo = false))
    val demos = listOf(track("demo:one", isDemo = true))
    val repository = FakeTrackRepository(locals)

    repository.setTracks(demos)

    assertEquals(LibrarySource.DEMO, repository.observeLibrary().first().source)
    assertTrue(repository.observeAvailableLocalTracks().first().isEmpty())
    assertEquals(demos, repository.demoTracks())

    repository.setTracks(locals)

    assertEquals(LibrarySource.LOCAL, repository.observeLibrary().first().source)
    assertEquals(locals, repository.observeAvailableLocalTracks().first())
    assertTrue(repository.demoTracks().isEmpty())

    repository.setTracks(emptyList())

    assertTrue(repository.observeTracks().first().isEmpty())
    assertTrue(repository.demoTracks().isEmpty())
  }

  @Test
  fun explicitHelpers_keepLocalAndDemoValuesSeparate() = runTest {
    val locals = listOf(track("local:one", isDemo = false))
    val demos = listOf(track("demo:one", isDemo = true))
    val repository = FakeTrackRepository()

    repository.setDemoTracks(demos)
    repository.setLocalTracks(locals)

    assertEquals(locals, repository.observeAvailableLocalTracks().first())
    assertEquals(demos, repository.demoTracks())
    assertEquals(LibrarySource.LOCAL, repository.observeLibrary().first().source)
    assertEquals(locals, repository.observeTracks().first())
  }

  @Test
  fun favoriteAndRecentOperations_modelPersistedLocalTracks() = runTest {
    val locals = (0 until 21).map { track("local:$it", isDemo = false) }
    val repository = FakeTrackRepository(locals)
    val existingId = locals.first().id

    assertTrue(repository.setFavorite(existingId, true))
    assertEquals(setOf(existingId), repository.observeFavoriteTrackIds().first())
    assertEquals(listOf(locals.first()), repository.observeFavoriteTracks().first())
    assertTrue(repository.setFavorite(existingId, false))
    assertTrue(repository.observeFavoriteTracks().first().isEmpty())

    locals.forEach { assertTrue(repository.recordRecent(it.id)) }
    assertEquals(20, repository.observeRecentTracks().first().size)
    assertEquals(locals.last(), repository.observeRecentTracks().first().first())
  }

  @Test
  fun userDataOperations_supportDemoTracksAndRejectMissingTracks() = runTest {
    val demos = (0 until 4).map { index -> track("demo:$index", isDemo = true) }
    val repository = FakeTrackRepository(demos)

    demos.forEach { demo ->
      assertTrue(repository.setFavorite(demo.id, true))
      assertTrue(repository.recordRecent(demo.id))
    }

    assertEquals(demos.map { it.id }.toSet(), repository.observeFavoriteTrackIds().first())
    assertEquals(demos.toSet(), repository.observeFavoriteTracks().first().toSet())
    assertEquals(demos.reversed(), repository.observeRecentTracks().first())
    assertTrue(!repository.setFavorite(TrackId("missing"), true))
    assertTrue(!repository.recordRecent(TrackId("missing")))
  }

  private fun track(id: String, isDemo: Boolean) =
    Track(
      id = TrackId(id),
      title = id,
      artist = null,
      album = null,
      durationMs = 1_000L,
      artworkUri = null,
      sourceUri = if (isDemo) "android.resource://app.yinyuehe/$id" else "content://media/$id",
      isDemo = isDemo,
    )
}
