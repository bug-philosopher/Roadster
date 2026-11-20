package com.oemplus.roadster.core

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Illumination(val label: String, val note: String) {
    AMBER("Amber", "Matches the NB2 (2001–05) gauge lighting"),
    GREEN("Green", "Matches NA and 1999–2000 NB1 lighting"),
    RED("Red", "Later Mazda red"),
    ICE("Ice", "Cool white-blue"),
}

enum class Dimmer { AUTO, DAY, DARK, NIGHT }

fun Dimmer.resolve(sunDown: Boolean): Pair<Boolean, Boolean> = when (this) {
    Dimmer.AUTO -> sunDown to sunDown
    Dimmer.DAY -> false to false
    Dimmer.DARK -> true to false
    Dimmer.NIGHT -> true to true
}
enum class SpeedUnit { MPH, KMH }
enum class TempUnit { F, C }
enum class AnalyzerInput { AUTO, SIMULATED }
enum class DispMode(val label: String) { SPECTRUM("Spectrum"), SCOPE("Scope"), SKYLINE("Skyline") }

data class Settings(
    val illumination: Illumination = Illumination.AMBER,
    val dimmer: Dimmer = Dimmer.AUTO,
    val fullscreen: Boolean = true,
    val marquee: Boolean = true,
    val clock24: Boolean = false,
    val brand: String = "ROADSTER",
    val speedUnit: SpeedUnit = SpeedUnit.MPH,
    val tempUnit: TempUnit = TempUnit.F,
    val weather: Boolean = false,
    val analyzerInput: AnalyzerInput = AnalyzerInput.AUTO,
    val dispMode: DispMode = DispMode.SPECTRUM,
    val naviPkg: String? = null,
    val linkPkg: String? = null,
    val sources: List<String> = emptyList(),
    val autostart: Boolean = false,
    val homeScreen: Boolean = false,
    val tripTracking: Boolean = true,
    val lastSourcePkg: String? = null,
)

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("roadster", Context.MODE_PRIVATE)
    private val defaults = Settings(clock24 = DateFormat.is24HourFormat(ctx))
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    @Synchronized
    fun update(block: (Settings) -> Settings) {
        val old = _settings.value
        val next = block(old)
        if (next == old) return
        _settings.value = next
        save(next)
    }

    private fun load(): Settings {
        val d = defaults
        return Settings(
            illumination = enumOr(sp.getString("illumination", null), d.illumination),
            dimmer = enumOr(sp.getString("dimmer", null), d.dimmer),
            fullscreen = sp.getBoolean("fullscreen", d.fullscreen),
            marquee = sp.getBoolean("marquee", d.marquee),
            clock24 = sp.getBoolean("clock24", d.clock24),
            brand = sp.getString("brand", null) ?: d.brand,
            speedUnit = enumOr(sp.getString("speedUnit", null), d.speedUnit),
            tempUnit = enumOr(sp.getString("tempUnit", null), d.tempUnit),
            weather = sp.getBoolean("weather", d.weather),
            analyzerInput = enumOr(sp.getString("analyzerInput", null), d.analyzerInput),
            dispMode = enumOr(sp.getString("dispMode", null), d.dispMode),
            naviPkg = sp.getString("naviPkg", null),
            linkPkg = sp.getString("linkPkg", null),
            sources = sp.getString("sources", null)?.split('|')?.filter { it.isNotBlank() } ?: d.sources,
            autostart = sp.getBoolean("autostart", d.autostart),
            homeScreen = sp.getBoolean("homeScreen", d.homeScreen),
            tripTracking = sp.getBoolean("tripTracking", d.tripTracking),
            lastSourcePkg = sp.getString("lastSourcePkg", null),
        )
    }

    private fun save(s: Settings) {
        sp.edit()
            .putString("illumination", s.illumination.name)
            .putString("dimmer", s.dimmer.name)
            .putBoolean("fullscreen", s.fullscreen)
            .putBoolean("marquee", s.marquee)
            .putBoolean("clock24", s.clock24)
            .putString("brand", s.brand)
            .putString("speedUnit", s.speedUnit.name)
            .putString("tempUnit", s.tempUnit.name)
            .putBoolean("weather", s.weather)
            .putString("analyzerInput", s.analyzerInput.name)
            .putString("dispMode", s.dispMode.name)
            .putString("naviPkg", s.naviPkg)
            .putString("linkPkg", s.linkPkg)
            .putString("sources", s.sources.joinToString("|"))
            .putBoolean("autostart", s.autostart)
            .putBoolean("homeScreen", s.homeScreen)
            .putBoolean("tripTracking", s.tripTracking)
            .putString("lastSourcePkg", s.lastSourcePkg)
            .apply()
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback
