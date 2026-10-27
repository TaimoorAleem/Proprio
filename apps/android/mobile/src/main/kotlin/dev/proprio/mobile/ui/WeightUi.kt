package dev.proprio.mobile.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.proprio.mobile.domain.WeightUnit

/** The user's weight unit (Settings). Provided once in [AppRoot]; kg when not provided (tests, previews). */
val LocalWeightUnit = compositionLocalOf { WeightUnit.KG }

/**
 * Load entry in the chosen unit. [loadKg] is the canonical value; the field keeps its own text so partial input such
 * as "62." survives, and re-syncs when the value changes from outside (the -/+ buttons) or the unit is switched.
 */
@Composable
fun LoadField(
    loadKg: Double?,
    onChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val unit = LocalWeightUnit.current
    var text by remember(unit) { mutableStateOf(unit.format(loadKg)) }
    LaunchedEffect(loadKg, unit) {
        if (unit.parseKg(text) != loadKg) text = unit.format(loadKg)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(unit.parseKg(it)) },
        label = { Text("Load (${unit.label})") },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )
}
