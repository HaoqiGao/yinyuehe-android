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
