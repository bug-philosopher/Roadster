package com.oemplus.roadster.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.apps.AppEntry
import com.oemplus.roadster.media.MediaHub
import com.oemplus.roadster.media.SessionView

@Composable
fun SourcesSheet(onDismiss: () -> Unit, onEdit: () -> Unit) {
    val sessions by Graph.media.sessions.collectAsStateWithLifecycle()
    val current by Graph.media.current.collectAsStateWithLifecycle()
    val btReady by Graph.media.btReady.collectAsStateWithLifecycle()
    val btPhone by Graph.media.btPhone.collectAsStateWithLifecycle()
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val host = LocalHost.current
    val launchers = remember(apps, settings.sources, sessions) {
        val active = sessions.map { it.pkg }.toSet()
        val pinned = settings.sources.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
        (pinned + Graph.apps.suggestSources()).distinctBy { it.pkg }.filter { it.pkg !in active }.take(8)
    }
    SheetFrame(
        "Source",
        onDismiss,
        trailing = { HardKey(onClick = onEdit, modifier = Modifier.size(110.u, 46.u)) { ink -> Txt("EDIT", 18, color = ink, bold = true) } },
    ) {
        Column(Modifier.heightIn(max = 470.u).verticalScroll(rememberScrollState())) {
            if (btReady && sessions.none { it.key == MediaHub.MTC_KEY || "bluetooth" in it.pkg }) {
                HardKey(
                    onClick = { Graph.media.startBluetooth(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().height(72.u),
                    lit = true,
                ) { ink ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.u), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Glyphs.Bluetooth, 30, ink)
                        Spacer(Modifier.width(14.u))
                        Txt("BT Audio", 21, color = ink, bold = true)
                        Spacer(Modifier.weight(1f))
                        Txt(
                            when (btPhone) {
                                true -> "Phone connected · press to play"
                                false -> "No phone connected yet"
                                null -> "Switch the sound to your phone"
                            },
                            16, color = ink.copy(alpha = 0.8f),
                        )
                    }
                }
                Spacer(Modifier.height(14.u))
            }
            if (sessions.isNotEmpty()) {
                Txt("Playing now", 18, color = Pal.Cream3, bold = true)
                Spacer(Modifier.height(10.u))
                sessions.forEach { s ->
                    SessionRow(s, selected = s.key == current?.key) {
                        Graph.media.activate(s.key)
                        onDismiss()
                    }
                    Spacer(Modifier.height(10.u))
                }
                Spacer(Modifier.height(8.u))
            }
            if (launchers.isNotEmpty()) {
                Txt("Open a source", 18, color = Pal.Cream3, bold = true)
                Spacer(Modifier.height(10.u))
                launchers.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.u)) {
                        row.forEach { a ->
                            HardKey(onClick = { host.launchApp(a.pkg); onDismiss() }, modifier = Modifier.weight(1f).height(64.u)) { ink ->
                                Row(Modifier.padding(horizontal = 12.u), verticalAlignment = Alignment.CenterVertically) {
                                    a.icon?.let { Image(it, null, Modifier.size(32.u)) }
                                    Spacer(Modifier.width(10.u))
                                    Txt(a.label, 18, color = ink, bold = true)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.u))
                }
            }
            if (sessions.isEmpty() && launchers.isEmpty()) {
                Txt("No media apps found on this unit yet.", 20, color = Pal.Cream2)
            }
        }
    }
}

@Composable
private fun SessionRow(s: SessionView, selected: Boolean, onSelect: () -> Unit) {
    HardKey(onClick = onSelect, modifier = Modifier.fillMaxWidth().height(76.u), lit = selected) { ink ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.u), verticalAlignment = Alignment.CenterVertically) {
            s.appIcon?.let { Image(it, null, Modifier.size(40.u)) }
            Spacer(Modifier.width(16.u))
            Column(Modifier.weight(1f)) {
                Txt(s.source, 21, color = ink, bold = true)
                Spacer(Modifier.height(4.u))
                Txt(listOfNotNull(s.title, s.artist).joinToString(" — ").ifEmpty { "—" }, 17, color = ink.copy(alpha = 0.75f))
            }
            Txt(if (s.playing) "PLAYING" else "PAUSED", 15, color = ink.copy(alpha = 0.8f), bold = true, tracking = 0.08f)
        }
    }
}

@Composable
fun PickAppSheet(target: KeyTarget, onDismiss: () -> Unit) {
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val suggested = remember(apps, target) {
        when (target) {
            KeyTarget.NAVI -> Graph.apps.suggestNavi()
            KeyTarget.LINK -> Graph.apps.suggestLink()
            KeyTarget.SOURCE -> Graph.apps.suggestSources()
        }
    }
    val rest = remember(apps, suggested) { apps.filter { a -> suggested.none { it.pkg == a.pkg } } }
    val title = when (target) {
        KeyTarget.NAVI -> "NAVI key opens…"
        KeyTarget.LINK -> "LINK key opens…"
        KeyTarget.SOURCE -> "Sources in the SRC list"
    }
    val chosen: (AppEntry) -> Boolean = { a ->
        when (target) {
            KeyTarget.NAVI -> a.pkg == settings.naviPkg
            KeyTarget.LINK -> a.pkg == settings.linkPkg
            KeyTarget.SOURCE -> a.pkg in settings.sources
        }
    }
    val choose: (AppEntry) -> Unit = { a ->
        when (target) {
            KeyTarget.NAVI -> { Graph.prefs.update { it.copy(naviPkg = a.pkg) }; onDismiss() }
            KeyTarget.LINK -> { Graph.prefs.update { it.copy(linkPkg = a.pkg) }; onDismiss() }
            KeyTarget.SOURCE -> Graph.prefs.update {
                it.copy(sources = if (a.pkg in it.sources) it.sources - a.pkg else it.sources + a.pkg)
            }
        }
    }
    SheetFrame(
        title,
        onDismiss,
        width = 1020f,
        trailing = {
            if (target != KeyTarget.SOURCE) {
                HardKey(
                    onClick = {
                        Graph.prefs.update { if (target == KeyTarget.NAVI) it.copy(naviPkg = null) else it.copy(linkPkg = null) }
                        onDismiss()
                    },
                    modifier = Modifier.size(150.u, 46.u),
                ) { ink -> Txt("AUTOMATIC", 17, color = ink, bold = true) }
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.u),
            modifier = Modifier.heightIn(max = 480.u),
            verticalArrangement = Arrangement.spacedBy(10.u),
            horizontalArrangement = Arrangement.spacedBy(10.u),
            contentPadding = PaddingValues(bottom = 6.u),
        ) {
            if (suggested.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Txt("Suggested", 18, color = Pal.Cream3, bold = true) }
                items(suggested, key = { "s" + it.pkg + it.activity }) { a ->
                    AppTile(a, onClick = { choose(a) }, onLong = null, lit = chosen(a), led = if (target == KeyTarget.SOURCE) chosen(a) else null)
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Txt("All apps", 18, color = Pal.Cream3, bold = true) }
            } else if (target == KeyTarget.LINK) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Txt("No CarPlay / Android Auto app recognised. It is usually named ZLINK, TLink, AutoKit or Car Auto.", 18, color = Pal.Cream2, maxLines = 2)
                }
            }
            items(rest, key = { "r" + it.pkg + it.activity }) { a ->
                AppTile(a, onClick = { choose(a) }, onLong = null, lit = chosen(a), led = if (target == KeyTarget.SOURCE) chosen(a) else null)
            }
        }
    }
}

@Composable
fun AppActionsSheet(app: AppEntry, onDismiss: () -> Unit) {
    val host = LocalHost.current
    val settings = LocalSettings.current
    SheetFrame(app.label, onDismiss, width = 760f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.icon?.let { Image(it, null, Modifier.size(64.u)) }
            Spacer(Modifier.width(18.u))
            Txt(app.pkg, 17, color = Pal.Cream3)
        }
        Spacer(Modifier.height(18.u))
        val inSources = app.pkg in settings.sources
        Column(verticalArrangement = Arrangement.spacedBy(10.u)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                TextKey("Open", { host.launchApp(app.pkg); onDismiss() }, width = 230f, lit = true)
                TextKey("Make NAVI key", { Graph.prefs.update { it.copy(naviPkg = app.pkg) }; onDismiss() }, width = 230f)
                TextKey("Make LINK key", { Graph.prefs.update { it.copy(linkPkg = app.pkg) }; onDismiss() }, width = 230f)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                TextKey(
                    if (inSources) "Remove from SRC" else "Add to SRC",
                    {
                        Graph.prefs.update { it.copy(sources = if (inSources) it.sources - app.pkg else it.sources + app.pkg) }
                        onDismiss()
                    },
                    width = 230f,
                )
                TextKey("App info", { host.openAppInfo(app.pkg); onDismiss() }, width = 230f)
            }
        }
    }
}
