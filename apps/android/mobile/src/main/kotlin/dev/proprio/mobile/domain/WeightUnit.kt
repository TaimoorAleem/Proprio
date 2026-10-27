package dev.proprio.mobile.domain

import kotlin.math.round
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact definition of the avoirdupois pound. */
const val KG_PER_LB = 0.45359237

/**
 * Display/input unit for loads. Storage is always kilograms (`labels.json` `load_kg`); this type only converts at the
 * UI edge. lb input is stored as kg rounded to 0.01 and shown as lb rounded to 0.1, which round-trips any lb value
 * typed to one decimal (the kg rounding error is at most 0.011 lb, far below the 0.05 lb display half-step), so 45,
 * 135, 225 and 315 lb come back exactly.
 */
@Serializable
enum class WeightUnit(val label: String, val stepLabel: String, private val kgPerUnit: Double, private val step: Double) {
    @SerialName("kg") KG("kg", "2.5", 1.0, 2.5),
    @SerialName("lb") LB("lb", "5", KG_PER_LB, 5.0),
    ;

    /** Value in this unit for a load stored in kg (rounded for display). */
    fun fromKg(kg: Double): Double = when (this) {
        KG -> round(kg * 100.0) / 100.0
        LB -> round(kg / kgPerUnit * 10.0) / 10.0
    }

    /** Canonical kilograms for a value typed in this unit. */
    fun toKg(value: Double): Double = when (this) {
        KG -> value
        LB -> round(value * kgPerUnit * 100.0) / 100.0
    }

    /** "135" or "62.5": the number only, no unit and no trailing ".0". */
    fun format(kg: Double?): String {
        if (kg == null) return ""
        val v = fromKg(kg)
        return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    }

    /** "135 lb" / "60 kg". */
    fun display(kg: Double): String = "${format(kg)} $label"

    /** Parses typed text ('.' or ',' decimal) into kilograms; null for blank or invalid text. */
    fun parseKg(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::toKg)

    /** The load after pressing the -/+ step button (2.5 kg or 5 lb), never below zero. */
    fun stepKg(currentKg: Double?, direction: Int): Double {
        val cur = fromKg(currentKg ?: 0.0)
        return toKg((cur + direction * step).coerceAtLeast(0.0))
    }

}
