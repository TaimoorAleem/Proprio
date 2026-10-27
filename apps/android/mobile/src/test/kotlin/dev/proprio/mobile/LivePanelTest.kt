package dev.proprio.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.ui.LivePanel
import dev.proprio.mobile.ui.ProprioTheme
import dev.proprio.mobile.ui.SessionActions
import dev.proprio.mobile.ui.SessionContent
import dev.proprio.protocol.LiveHealth
import dev.proprio.protocol.LiveSegmentKind
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.WatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LivePanelTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun controller(link: FakeWatchLink = FakeWatchLink()) = LivePreviewController(scope, link, { 10_000L }, Dispatchers.Unconfined)

    private fun fill(c: LivePreviewController, health: LiveHealth = LiveHealth(1234, 1230, 49.8f, 50.2f, 0, 0, 0, false)) {
        for (i in 0L until 5) {
            c.model.onBatch(liveBatch(i, health = health, kind = LiveSegmentKind.SET, segId = 12), 10_000L - (4 - i) * 10)
        }
    }

    @Test
    fun panelRendersChartsAndTheHealthRowWithFakeData() {
        val link = FakeWatchLink()
        val c = controller(link)
        fill(c)
        compose.setContent { ProprioTheme { LivePanel(c, expanded = false, onToggleExpand = {}, tickMs = 0) } }

        compose.onNodeWithTag("live_panel").assertIsDisplayed()
        compose.onNodeWithTag("live_chart_acc").assertExists()
        compose.onNodeWithTag("live_chart_gyr").assertExists()
        for (tag in listOf("link", "segment", "acc", "gyr", "gaps", "sat", "recorder", "preview")) {
            compose.onNodeWithTag("live_health_$tag").assertExists()
        }
        // text content of the chips
        val link1 = compose.onNodeWithTag("live_health_link").fetchSemanticsNode()
        assertTrue(allText(link1).contains("LIVE"))
        assertTrue(allText(compose.onNodeWithTag("live_health_acc").fetchSemanticsNode()).contains("49.8 Hz OK"))
        assertTrue(allText(compose.onNodeWithTag("live_health_segment").fetchSemanticsNode()).contains("SET #12 recording"))
        assertTrue(allText(compose.onNodeWithTag("live_health_gaps").fetchSemanticsNode()).contains("0 OK"))
        assertTrue(allText(compose.onNodeWithTag("live_health_preview").fetchSemanticsNode()).contains("0"))
        assertTrue(allText(compose.onNodeWithTag("live_health_counts").fetchSemanticsNode()).contains("acc 1234"))
        // the lease is requested as soon as the panel is visible
        compose.waitForIdle()
        assertEquals("live/subscribe:10000", link.liveCommands.first())
    }

    @Test
    fun badIndicatorsAreSpelledOutInText() {
        val c = controller()
        fill(c, LiveHealth(10, 10, 30f, 50f, 4, 1, 2, false))
        compose.setContent { ProprioTheme { LivePanel(c, expanded = false, onToggleExpand = {}, tickMs = 0) } }
        assertTrue(allText(compose.onNodeWithTag("live_health_acc").fetchSemanticsNode()).contains("CHECK"))
        assertTrue(allText(compose.onNodeWithTag("live_health_gaps").fetchSemanticsNode()).contains("4 BAD"))
        assertTrue(allText(compose.onNodeWithTag("live_health_sat").fetchSemanticsNode()).contains("1 CHECK"))
        assertTrue(allText(compose.onNodeWithTag("live_health_recorder").fetchSemanticsNode()).contains("2 dropped"))
    }

    @Test
    fun noDataIsShownBeforeTheFirstBatch() {
        val c = controller()
        compose.setContent { ProprioTheme { LivePanel(c, expanded = false, onToggleExpand = {}, tickMs = 0) } }
        assertTrue(allText(compose.onNodeWithTag("live_health_link").fetchSemanticsNode()).contains("NO DATA"))
    }

    @Test
    fun sessionScreenShowsTheLivePanelOnlyWhileRecording() {
        val c = controller()
        fill(c)
        val actions = SessionActions({}, {}, {}, {}, {}, {}, { _, _, _, _ -> }, {}, {}, {}, {})
        val recording = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            ProprioTheme {
                val watch = if (recording.value) WatchStatus(WatchStatus.State.RECORDING, "s1", SessionKind.GYM, null, 5.0, 50.0, 50.0) else WatchStatus(WatchStatus.State.IDLE)
                SessionContent(SessionUiState(connected = true, watch = watch), actions, live = c, liveTickMs = 0)
            }
        }
        compose.onNodeWithTag("live_panel").assertExists()
        recording.value = false
        compose.waitForIdle()
        compose.onNodeWithTag("live_panel").assertDoesNotExist()
    }

    private fun allText(node: androidx.compose.ui.semantics.SemanticsNode): String {
        val own = (if (androidx.compose.ui.semantics.SemanticsProperties.Text in node.config) node.config[androidx.compose.ui.semantics.SemanticsProperties.Text] else null)?.joinToString("") { it.text }.orEmpty()
        return own + " " + node.children.joinToString(" ") { allText(it) }
    }
}
