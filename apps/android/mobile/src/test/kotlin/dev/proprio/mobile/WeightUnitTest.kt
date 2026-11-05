package dev.proprio.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.proprio.mobile.TestSupport.SID
import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.data.FileSettingsStore
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.MemorySettingsStore
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.UserSettings
import dev.proprio.mobile.domain.KG_PER_LB
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.mobile.domain.WeightUnit
import dev.proprio.mobile.ui.LocalWeightUnit
import dev.proprio.mobile.ui.ProprioTheme
import dev.proprio.mobile.ui.SessionScreen
import dev.proprio.mobile.ui.SettingsScreen
import dev.proprio.protocol.Consent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

class WeightUnitConversionTest {
    @Test
    fun kgPerLbIsTheExactDefinition() {
        assertEquals(0.45359237, KG_PER_LB, 0.0)
    }

    @Test
    fun typicalPlateLoadsRoundTripExactlyThroughKg() {
        for (lb in listOf(5.0, 10.0, 25.0, 35.0, 45.0, 95.0, 135.0, 185.0, 225.0, 275.0, 315.0, 405.0, 495.0)) {
            val kg = WeightUnit.LB.toKg(lb)
            assertEquals("kg has at most 2 decimals for $lb", kg, Math.round(kg * 100) / 100.0, 0.0)
            assertEquals(lb, WeightUnit.LB.fromKg(kg), 0.0)
        }
        assertEquals(20.41, WeightUnit.LB.toKg(45.0), 0.0)
        assertEquals(61.23, WeightUnit.LB.toKg(135.0), 0.0)
        assertEquals(102.06, WeightUnit.LB.toKg(225.0), 0.0)
        assertEquals(142.88, WeightUnit.LB.toKg(315.0), 0.0)
        assertEquals("315", WeightUnit.LB.format(142.88))
        assertEquals("225", WeightUnit.LB.format(102.06))
    }

    @Test
    fun everyOneDecimalLbValueRoundTrips() {
        var tenths = 0
        while (tenths <= 10_000) { // 0 to 1000 lb in 0.1 steps
            val lb = tenths / 10.0
            assertEquals("lb=$lb", lb, WeightUnit.LB.fromKg(WeightUnit.LB.toKg(lb)), 1e-9)
            tenths++
        }
    }

    @Test
    fun kgIsStoredAsTypedAndFormatsWithoutTrailingZero() {
        assertEquals(62.5, WeightUnit.KG.toKg(62.5), 0.0)
        assertEquals("62.5", WeightUnit.KG.format(62.5))
        assertEquals("60", WeightUnit.KG.format(60.0))
        assertEquals("", WeightUnit.KG.format(null))
        assertEquals("60 kg", WeightUnit.KG.display(60.0))
        assertEquals("135 lb", WeightUnit.LB.display(61.23))
    }

    @Test
    fun parsingAcceptsCommaAndRejectsGarbage() {
        assertEquals(WeightUnit.LB.toKg(132.5), WeightUnit.LB.parseKg("132,5")!!, 0.0)
        assertNull(WeightUnit.LB.parseKg(""))
        assertNull(WeightUnit.LB.parseKg("abc"))
        assertNull(WeightUnit.KG.parseKg("NaN"))
    }

    @Test
    fun stepButtonsMove2_5KgOr5Lb() {
        assertEquals(62.5, WeightUnit.KG.stepKg(60.0, 1), 0.0)
        assertEquals(0.0, WeightUnit.KG.stepKg(1.0, -1), 0.0)
        assertEquals(WeightUnit.LB.toKg(140.0), WeightUnit.LB.stepKg(WeightUnit.LB.toKg(135.0), 1), 0.0)
        assertEquals(WeightUnit.LB.toKg(130.0), WeightUnit.LB.stepKg(WeightUnit.LB.toKg(135.0), -1), 0.0)
    }

    @Test
    fun settingsDefaultToKgAndPersistTheUnit() {
        val dir = kotlin.io.path.createTempDirectory("unit").toFile()
        val file = File(dir, "settings.json")
        file.writeText("""{"participantId":"P01","leftWristAcknowledged":true}""") // an old file without the field
        val store = FileSettingsStore(file)
        assertEquals(WeightUnit.KG, store.settings.value.weightUnit)
        SettingsViewModel(store).setWeightUnit(WeightUnit.LB)
        assertEquals(WeightUnit.LB, FileSettingsStore(file).settings.value.weightUnit)
        assertTrue(file.readText().contains("\"lb\""))
        assertEquals("P01", FileSettingsStore(file).settings.value.participantId)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeightUnitUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun coordinator(link: FakeWatchLink, store: SessionStore) = SessionCoordinator(
        scope, link, store, SessionImporter(store),
        MemorySettingsStore(UserSettings("P01", true, "2026-01-01T00:00:00Z", true, countdownMs = 0)),
        io = Dispatchers.Unconfined,
    )

    @Test
    fun entryFieldShowsThePoundUnitAndLabelsAreStoredInKilograms() {
        val link = FakeWatchLink { SID }
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })
        val coordinator = coordinator(link, store)
        compose.setContent {
            CompositionLocalProvider(LocalWeightUnit provides WeightUnit.LB) { ProprioTheme { SessionScreen(coordinator) } }
        }
        compose.onNodeWithText("Start gym session").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Load (lb)").assertExists() // the field label carries the unit
        compose.onNodeWithText("Load (kg)").assertDoesNotExist()
        compose.onNodeWithText("-5").assertExists()
        compose.onNodeWithTag("load").performTextReplacement("225")
        compose.onNodeWithTag("load").assertTextContains("225")
        compose.onNodeWithText("+5").performClick() // 230 lb
        compose.waitForIdle()
        compose.onNodeWithTag("load").assertTextContains("230")
        compose.onNodeWithText("-5").performClick() // back to 225
        compose.waitForIdle()
        compose.onNodeWithTag("load").assertTextContains("225")

        compose.onNodeWithText("Start set").performScrollTo().performClick()
        compose.waitForIdle()
        val label = LabelEditor.find(store.readLabels(SID)!!, 1)!!
        assertEquals(102.06, label.loadKg!!, 0.0) // canonical kilograms on disk
        val json = File(store.dir(SID), "labels.json").readText()
        assertTrue(json, json.contains("\"load_kg\": 102.06") || json.contains("\"load_kg\":102.06"))
        assertTrue(!json.contains("lb"))
    }

    @Test
    fun existingKgLabelsStayUntouchedAndDisplayInPounds() {
        assertEquals("132.3", WeightUnit.LB.format(60.0))
        assertEquals("60", WeightUnit.KG.format(60.0)) // switching back shows the stored value again
    }

    @Test
    fun kgIsTheDefaultUnitInTheField() {
        val link = FakeWatchLink { SID }
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })
        val coordinator = coordinator(link, store)
        compose.setContent { ProprioTheme { SessionScreen(coordinator) } }
        compose.onNodeWithText("Start gym session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Load (kg)").assertExists()
        compose.onNodeWithText("+2.5").assertExists()
    }

    @Test
    fun settingsToggleOffersBothUnitsAndReportsTheChoice() {
        var chosen: WeightUnit? = null
        compose.setContent {
            ProprioTheme {
                SettingsScreen(UserSettings("P01", true, null, true), firstRun = false, onSave = { _, _, _ -> }, onWeightUnit = { chosen = it })
            }
        }
        compose.onNodeWithTag("unit_kg").performScrollTo().assertExists()
        compose.onNodeWithTag("unit_lb").performScrollTo().performClick()
        assertEquals(WeightUnit.LB, chosen)
    }
}
