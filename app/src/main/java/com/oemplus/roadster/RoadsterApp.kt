package com.oemplus.roadster

import android.app.Application
import com.oemplus.roadster.apps.AppCatalog
import com.oemplus.roadster.core.AccessHub
import com.oemplus.roadster.core.Prefs
import com.oemplus.roadster.location.GpsHub
import com.oemplus.roadster.location.SunHub
import com.oemplus.roadster.location.TripComputer
import com.oemplus.roadster.location.WeatherHub
import com.oemplus.roadster.media.Analyzer
import com.oemplus.roadster.media.MediaHub
import com.oemplus.roadster.media.NavHub
import com.oemplus.roadster.perf.PerfTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RoadsterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

object Graph {
    lateinit var app: Application
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var access: AccessHub
        private set
    lateinit var apps: AppCatalog
        private set
    lateinit var nav: NavHub
        private set
    lateinit var media: MediaHub
        private set
    lateinit var analyzer: Analyzer
        private set
    lateinit var gps: GpsHub
        private set
    lateinit var trips: TripComputer
        private set
    lateinit var perf: PerfTimer
        private set
    lateinit var sun: SunHub
        private set
    lateinit var weather: WeatherHub
        private set

    fun init(app: Application) {
        if (::prefs.isInitialized) return
        this.app = app
        prefs = Prefs(app)
        access = AccessHub(app)
        apps = AppCatalog(app, scope)
        nav = NavHub()
        media = MediaHub(app, prefs, apps, scope)
        analyzer = Analyzer(app, media, prefs, scope)
        media.audioProbe = analyzer
        gps = GpsHub(app)
        trips = TripComputer(app, gps, scope)
        perf = PerfTimer(app, gps, prefs, scope)
        sun = SunHub(gps, scope)
        weather = WeatherHub(app, prefs, gps, scope)
        media.connect()
    }
}
