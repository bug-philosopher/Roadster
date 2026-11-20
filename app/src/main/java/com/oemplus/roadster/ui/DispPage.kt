package com.oemplus.roadster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.core.AnalyzerInput
import com.oemplus.roadster.core.DispMode
import com.oemplus.roadster.media.AnalyzerStatus

@Composable
fun DispPage(full: Boolean, onToggleFull: () -> Unit, reduceMotion: Boolean) {
    val settings = LocalSettings.current
    val host = LocalHost.current
    val mode = settings.dispMode
    val status by Graph.analyzer.status.collectAsStateWithLifecycle()
    val cur by Graph.media.current.collectAsStateWithLifecycle()
    val skyline = mode == DispMode.SKYLINE
    val (anim, frame) = rememberSpectrum(if (skyline) 24 else 32, ambientFps = if (skyline && !reduceMotion) 15 else 0)

    GlassPanel(Modifier.fillMaxSize(), pad = 18f) {
        if (!full) {
            Row(Modifier.fillMaxWidth().height(54.u), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(cur?.title ?: "Nothing playing", 26, bold = true)
                    Spacer(Modifier.height(6.u))
                    Txt(listOfNotNull(cur?.artist, cur?.source).joinToString("  ·  ").ifEmpty { " " }, 19, color = Pal.Cream2)
                }
                Spacer(Modifier.width(16.u))
                Row(horizontalArrangement = Arrangement.spacedBy(8.u)) {
                    DispMode.entries.forEach { m ->
                        HardKey(
                            onClick = { Graph.prefs.update { it.copy(dispMode = m) } },
                            modifier = Modifier.size(146.u, 52.u),
                            lit = m == mode,
                        ) { ink -> Txt(m.label, 19, color = ink, bold = true) }
                    }
                }
                Spacer(Modifier.width(18.u))
                HardKey(onClick = onToggleFull, modifier = Modifier.size(104.u, 52.u)) { ink ->
                    Txt("FULL", 19, color = ink, bold = true, tracking = 0.08f)
                }
            }
            Spacer(Modifier.height(16.u))
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null, enabled = full) { onToggleFull() },
        ) {
            when (mode) {
                DispMode.SPECTRUM -> Column(Modifier.fillMaxSize()) {
                    SpectrumMeter(anim, frame, rows = 22, modifier = Modifier.weight(1f).fillMaxWidth(), gapX = 5f, gapY = 4f)
                    Spacer(Modifier.height(10.u))
                    FreqScale(anim.bands, Modifier.fillMaxWidth().height(22.u), gapX = 5f)
                }
                DispMode.SCOPE -> ScopeView(anim, frame, Modifier.fillMaxSize())
                DispMode.SKYLINE -> SkylineView(anim, frame, Modifier.fillMaxSize(), reduceMotion)
            }
            analyzerNote(status)?.let { note ->
                Txt(
                    note, 15, color = Pal.Cream2,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.u)
                        .background(Pal.Glass.copy(alpha = if (skyline) 0.82f else 0f), RoundedCornerShape(3.u))
                        .padding(horizontal = 8.u, vertical = 4.u),
                )
            }
        }
        if (!full && status == AnalyzerStatus.NEEDS_PERMISSION && settings.analyzerInput == AnalyzerInput.AUTO) {
            Spacer(Modifier.height(14.u))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    "To follow the real music, allow the microphone permission. Android uses it to gate the " +
                        "audio-output visualizer; nothing is recorded.",
                    18, color = Pal.Cream2, maxLines = 2, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.u))
                TextKey("Allow", host::requestMicrophone, lit = true, width = 150f)
            }
        }
    }
}
