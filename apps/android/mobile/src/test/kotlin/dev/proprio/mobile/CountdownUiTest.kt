package dev.proprio.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.proprio.mobile.TestSupport.SID
import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.MemorySettingsStore
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.UserSettings
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.mobile.ui.LivePanel
import dev.proprio.mobile.ui.ProprioTheme
import dev.proprio.mobile.ui.SessionScreen
import dev.proprio.mobile.ui.SettingsScreen
import dev.proprio.protocol.Consent
import dev.proprio.protocol.SessionKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hands-free set start on the phone: the command carries the configured countdown, the UI shows and cancels it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CountdownUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val link = FakeWatchLink { SID }
    private val settings = MemorySettingsStore(UserSettings("P01", true, "2026-01-01T00:00:00Z", true, countdownMs = 5_000))
    private lateinit var store: SessionStore
    private lateinit var coordinator: SessionCoordinator

    @After fun tearDown() = scope.cancel()

    private fun setUpCoordinator() {
        store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })
        coordinator = SessionCoordinator(scope, link, store, SessionImporter(store), settings, io = Dispatchers.Unconfined)
    }

    @Test
    fun startSetSendsTheConfiguredCountdownAndOffMeansImmediate() {
        setUpCoordinator()
        coordinator.startSession(SessionKind.GYM)
        coordinator.startSet()
        assertEquals("segment/start:SET@5000", link.commands.last())
        // the watch is counting down: no segment is open yet and the phone knows the remaining time
        assertNull(coordinator.state.value.open)
        assertEquals(5_000L, coordinator.state.value.countdownMs)
        // a second press while counting is ignored by the watch (the fake mirrors that)
        coordinator.startSet()
        assertEquals(5_000L, coordinator.state.value.countdownMs)

        coordinator.cancelCountdown()
        assertEquals("segment/cancel", link.commands.last())
        assertNull(coordinator.state.value.countdownMs)

        settings.update { it.copy(countdownMs = 0) }
        coordinator.startSet()
        assertEquals("segment/start:SET", link.commands.last())
        assertEquals(1, coordinator.state.value.open?.segmentId)
    }

    @Test
    fun labelIsCreatedWhenTheCountdownEndsWithTheConfigFromThePress() {
        setUpCoordinator()
        coordinator.startSession(SessionKind.GYM)
        coordinator.setConfig { it.copy(exercise = "squat", loadKg = 100.0) }
        coordinator.startSet()
        coordinator.setConfig { it.copy(exercise = "row") } // edits during the countdown must not leak in
        assertNull(store.readLabels(SID)?.let { LabelEditor.find(it, 1) })
        runBlocking { link.finishCountdown() }
        assertEquals("squat", LabelEditor.find(store.readLabels(SID)!!, 1)!!.exercise)
        assertEquals(1, coordinator.state.value.open?.segmentId)
        assertNull(coordinator.state.value.countdownMs)
    }

    @Test
    fun sessionScreenShowsTheCountdownAndCancelsIt() {
        setUpCoordinator()
        compose.setContent { ProprioTheme { SessionScreen(coordinator) } }
        compose.onNodeWithText("Start gym session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start set in 5 s").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("countdown_text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("countdown_text").assertTextEquals("Starting in 5")
        link.tickCountdown(3_200)
        compose.waitForIdle()
        compose.onNodeWithTag("countdown_text").assertTextEquals("Starting in 4")
        assertTrue(allText(compose.onNodeWithTag("open_segment")).contains("Starting in 4"))

        compose.onNodeWithTag("cancel_countdown").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("countdown_card").assertDoesNotExist()
        compose.onNodeWithText("Start set in 5 s").assertExists()
        assertEquals("segment/cancel", link.commands.last())
    }

    @Test
    fun liveSegmentChipReadsStartingInDuringTheCountdown() {
        val c = LivePreviewController(scope, link, { 10_000L }, Dispatchers.Unconfined)
        compose.setContent { ProprioTheme { LivePanel(c, expanded = false, onToggleExpand = {}, tickMs = 0, countdownMs = 2_500) } }
        assertTrue(allText(compose.onNodeWithTag("live_health_segment")).contains("Starting in 3"))
    }

    @Test
    fun settingsOffersTheCountdownChoices() {
        var chosen: Long? = null
        compose.setContent {
            ProprioTheme {
                SettingsScreen(
                    UserSettings("P01", true, null, true, countdownMs = 5_000), firstRun = false,
                    onSave = { _, _, _ -> }, onCountdown = { chosen = it },
                )
            }
        }
        for (s in listOf(0, 3, 5, 10)) compose.onNodeWithTag("countdown_$s").performScrollTo().assertExists()
        compose.onNodeWithTag("countdown_10").performScrollTo().performClick()
        assertEquals(10_000L, chosen)
    }

    @Test
    fun settingsViewModelClampsAndPersistsTheCountdown() {
        val vm = SettingsViewModel(settings)
        vm.setCountdown(3_000)
        assertEquals(3_000L, settings.settings.value.countdownMs)
        vm.setCountdown(99_999_999)
        assertEquals(dev.proprio.protocol.SegmentStartCommand.MAX_COUNTDOWN_MS, settings.settings.value.countdownMs)
    }

    private fun allText(n: androidx.compose.ui.test.SemanticsNodeInteraction): String = allText(n.fetchSemanticsNode())

    private fun allText(node: androidx.compose.ui.semantics.SemanticsNode): String {
        val own = (if (androidx.compose.ui.semantics.SemanticsProperties.Text in node.config) node.config[androidx.compose.ui.semantics.SemanticsProperties.Text] else null)
            ?.joinToString("") { it.text }.orEmpty()
        return own + " " + node.children.joinToString(" ") { allText(it) }
    }
}
