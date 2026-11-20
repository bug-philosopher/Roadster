package com.oemplus.roadster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oemplus.roadster.Graph
import com.oemplus.roadster.apps.AppEntry

@Composable
fun AppsPage(onActions: (AppEntry) -> Unit) {
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    val loaded by Graph.apps.loaded.collectAsStateWithLifecycle()
    val host = LocalHost.current
    GlassPanel(
        Modifier.fillMaxSize(),
        caption = "Applications",
        trailing = { Txt(if (loaded) "${apps.size} installed · hold a key for options" else "", 17, color = Pal.Cream3) },
    ) {
        when {
            !loaded -> Txt("Reading installed apps…", 22, color = Pal.Cream2)
            apps.isEmpty() -> Txt("No launchable apps found.", 22, color = Pal.Cream2)
            else -> {
                val state = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(156.u),
                        state = state,
                        modifier = Modifier.fillMaxSize().padding(end = 22.u),
                        verticalArrangement = Arrangement.spacedBy(12.u),
                        horizontalArrangement = Arrangement.spacedBy(12.u),
                        contentPadding = PaddingValues(top = 4.u, bottom = 10.u),
                    ) {
                        items(apps, key = { it.pkg + "/" + it.activity }) { a ->
                            AppTile(a, onClick = { host.launchApp(a.pkg) }, onLong = { onActions(a) })
                        }
                    }
                    GridScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.u))
                }
            }
        }
    }
}

@Composable
fun AppTile(a: AppEntry, onClick: () -> Unit, onLong: (() -> Unit)?, lit: Boolean = false, led: Boolean? = null) {
    HardKey(onClick = onClick, onLongClick = onLong, modifier = Modifier.fillMaxWidth().height(134.u), lit = lit, led = led) { ink ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            a.icon?.let { Image(it, null, Modifier.size(60.u)) }
            Spacer(Modifier.height(10.u))
            Txt(a.label, 17, color = ink, maxLines = 2, align = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.u), lineHeight = 1.15f)
        }
    }
}

@Composable
fun GridScrollbar(state: LazyGridState, modifier: Modifier) {
    val k = pxPerU()
    Canvas(modifier) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        val r = CornerRadius(size.width / 2f)
        drawRoundRect(Pal.Ink, cornerRadius = r)
        if (total == 0 || visible >= total) return@Canvas
        val frac = visible / total.toFloat()
        val h = (size.height * frac).coerceAtLeast(40f * k)
        val maxFirst = (total - visible).coerceAtLeast(1)
        val top = (size.height - h) * (state.firstVisibleItemIndex / maxFirst.toFloat()).coerceIn(0f, 1f)
        drawRoundRect(Pal.Cream3, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = r)
    }
}
