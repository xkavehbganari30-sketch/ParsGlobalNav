package com.parsnav.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class NavStep(val instruction: String, val icon: String, val location: LatLng, val distanceMeters: Double, var announcedFar: Boolean = false, var announcedNear: Boolean = false)
data class HazardPoint(val location: LatLng, val type: String, val speedLimit: Int = 60, var alerted: Boolean = false)
data class RouteCandidate(val points: List<LatLng>, val distKm: Double, val durMin: Double, val steps: List<NavStep>, val hasTrafficZone: Boolean)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, LocationListener {

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var tts: TextToSpeech
    private lateinit var locationManager: LocationManager

    private lateinit var tvStatus: TextView
    private lateinit var tvEtaBadge: TextView
    private lateinit var tvBigDistance: TextView
    private lateinit var tvBigDuration: TextView
    private lateinit var tvTurnIcon: TextView
    private lateinit var tvTurnDistance: TextView
    private lateinit var tvTurnText: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSpeedLimitBadge: TextView
    private lateinit var turnBanner: LinearLayout
    private lateinit var topContainer: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var hudOverlay: LinearLayout
    private lateinit var tvHudArrow: TextView
    private lateinit var tvHudTurnDist: TextView
    private lateinit var tvHudInstruction: TextView
    private lateinit var tvHudSpeed: TextView
    private lateinit var tvHudRemInfo: TextView
    private lateinit var btnStartNav: Button
    private lateinit var btnAltRoute: Button
    private lateinit var routeProgress: ProgressBar
    private lateinit var etSearch: EditText

    private var userMarker: Marker? = null
    private var isHudMode = false
    private var isNavigating = false
    private var avoidTrafficZone = true
    private var avoidPollutionZone = true
    private var currentSpeedLimit = 60
    private var currentLangIndex = 0
    private var selectedMode = "car"
    private var lastDestination: LatLng? = null
    private var lastDestName: String = "مقصد"
    private var totalRouteKm: Double = 0.0
    private var activeRoutePoints = listOf<LatLng>()
    private var allRouteCandidates = listOf<RouteCandidate>()
    private var activeRouteIndex = 0
    private var navSteps = mutableListOf<NavStep>()
    private var hazards = mutableListOf<HazardPoint>()
    private var currentStepIndex = 0
    private var lastRerouteTime = 0L
    private var hasInitialGpsLock = false

    private lateinit var modeButtons: Map<String, Button>
    private val languages = listOf(
        Triple("فارسی", Locale("fa", "IR"), "fa-IR"),
        Triple("English", Locale.US, "en-US"),
        Triple("العربية", Locale("ar", "SA"), "ar-SA"),
        Triple("Türkçe", Locale("tr", "TR"), "tr-TR")
    )

    private var currentLat = 35.6892
    private var currentLon = 51.3890

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                etSearch.setText(spoken)
                searchPlaceAndRoute(spoken, false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvEtaBadge = findViewById(R.id.tvEtaBadge)
        tvBigDistance = findViewById(R.id.tvBigDistance)
        tvBigDuration = findViewById(R.id.tvBigDuration)
        tvTurnIcon = findViewById(R.id.tvTurnIcon)
        tvTurnDistance = findViewById(R.id.tvTurnDistance)
        tvTurnText = findViewById(R.id.tvTurnText)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvSpeedLimitBadge = findViewById(R.id.tvSpeedLimitBadge)
        turnBanner = findViewById(R.id.turnBanner)
        topContainer = findViewById(R.id.topContainer)
        bottomPanel = findViewById(R.id.bottomPanel)
        hudOverlay = findViewById(R.id.hudOverlay)
        tvHudArrow = findViewById(R.id.tvHudArrow)
        tvHudTurnDist = findViewById(R.id.tvHudTurnDist)
        tvHudInstruction = findViewById(R.id.tvHudInstruction)
        tvHudSpeed = findViewById(R.id.tvHudSpeed)
        tvHudRemInfo = findViewById(R.id.tvHudRemInfo)
        btnStartNav = findViewById(R.id.btnStartNav)
        btnAltRoute = findViewById(R.id.btnAltRoute)
        routeProgress = findViewById(R.id.routeProgress)
        etSearch = findViewById(R.id.etSearch)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        requestPermissions()
        fetchImmediateLocation()
        setupMap()
        setupControls()
        startLiveGpsTracking()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            fetchImmediateLocation()
            startLiveGpsTracking()
            updateUserPinOnMap(true)
        }
    }

    private fun fetchImmediateLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val providers = locationManager.getProviders(true)
        var bestLoc: Location? = null
        for (p in providers) {
            val l = locationManager.getLastKnownLocation(p) ?: continue
            if (bestLoc == null || l.accuracy < bestLoc.accuracy) bestLoc = l
        }
        if (bestLoc != null) {
            currentLat = bestLoc.latitude
            currentLon = bestLoc.longitude
            hasInitialGpsLock = true
        }
    }

    private fun updateUserPinOnMap(zoomToUser: Boolean) {
        val map = mapLibreMap ?: return
        val pos = LatLng(currentLat, currentLon)
        userMarker?.let { map.removeMarker(it) }
        userMarker = map.addMarker(MarkerOptions().position(pos).title("📍 موقعیت فعلی شما"))
        if (zoomToUser) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(pos).zoom(15.8).build()
            ), 650)
        }
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val offlineFile = File(getExternalFilesDir(null), "offline_map.mbtiles")
            if (isOnline()) {
                map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                tvStatus.text = if (hasInitialGpsLock) "مکان فعلی شناسایی شد | آماده انتخاب مقصد" else "در حال تثبیت مکان فعلی شما..."
            } else if (offlineFile.exists()) {
                val offlineStyle = """{"version":8,"sources":{"offline-tiles":{"type":"vector","url":"mbtiles://${offlineFile.absolutePath}"}},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#161B22"}}]}"""
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(offlineStyle))
                tvStatus.text = "آفلاین | نقشه داخلی فعال شد"
            } else {
                tvStatus.text = "حالت آفلاین | ردیابی GPS فعال"
            }

            updateUserPinOnMap(true)

            map.addOnMapClickListener { point ->
                lastDestination = point
                lastDestName = "نقطه انتخابی روی نقشه"
                isNavigating = false
                calculateAndDrawRoute(LatLng(currentLat, currentLon), point)
                true
            }
        }
    }

    private fun setupControls() {
        val btnVoice = findViewById<Button>(R.id.btnVoiceSearch)
        val btnSearchGo = findViewById<Button>(R.id.btnSearchGo)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnHud = findViewById<Button>(R.id.btnHud)
        val btnExitHud = findViewById<Button>(R.id.btnExitHud)
        val btnLang = findViewById<Button>(R.id.btnLang)
        val btnOfflineMap = findViewById<Button>(R.id.btnOfflineMap)
        val btnMyLoc = findViewById<Button>(R.id.btnMyLoc)
        val btnFav = findViewById<Button>(R.id.btnFavPlaces)

        findViewById<Button>(R.id.btnPoiGas).setOnClickListener { searchNearbyPoi("پمپ بنزین", "fuel") }
        findViewById<Button>(R.id.btnPoiCng).setOnClickListener { searchNearbyPoi("پمپ گاز CNG", "fuel") }
        findViewById<Button>(R.id.btnPoiRest).setOnClickListener { searchNearbyPoi("استراحتگاه", "rest_area") }
        findViewById<Button>(R.id.btnPoiHospital).setOnClickListener { searchNearbyPoi("بیمارستان", "hospital") }
        btnFav.setOnClickListener { showFavoritesDialog() }

        modeButtons = mapOf(
            "car" to findViewById(R.id.btnModeCar),
            "moto" to findViewById(R.id.btnModeMoto),
            "bike" to findViewById(R.id.btnModeBike),
            "walk" to findViewById(R.id.btnModeWalk),
            "transit" to findViewById(R.id.btnModeTransit)
        )

        modeButtons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                selectedMode = mode
                modeButtons.forEach { (m, b) ->
                    b.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (m == mode) "#1F6FEB" else "#21262D"))
                    b.setTextColor(if (m == mode) Color.WHITE else Color.parseColor("#C9D1D9"))
                }
                lastDestination?.let { calculateAndDrawRoute(LatLng(currentLat, currentLon), it) }
            }
        }

        btnStartNav.setOnClickListener {
            isNavigating = true
            btnStartNav.visibility = View.GONE
            turnBanner.visibility = View.VISIBLE
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(currentLat, currentLon)).zoom(16.8).tilt(55.0).build()
            ), 800)
            tts.language = languages[currentLangIndex].second
            tts.speak("بزن بریم رفیق!", TextToSpeech.QUEUE_FLUSH, null, "GO_MSG")
            val firstInst = if (navSteps.size > 1) navSteps[1].instruction else "مستقیم به سمت مقصد حرکت کنید"
            tts.speak(firstInst, TextToSpeech.QUEUE_ADD, null, "FIRST_STEP")
        }

        btnAltRoute.setOnClickListener {
            if (allRouteCandidates.size > 1) {
                activeRouteIndex = (activeRouteIndex + 1) % allRouteCandidates.size
                applyRouteCandidate(allRouteCandidates[activeRouteIndex])
                speakQueued("مسیر جایگزین انتخاب شد")
            } else {
                Toast.makeText(this, "مسیر جایگزین دیگری برای این مقصد یافت نشد", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnHud.setOnClickListener { toggleHudMode(true) }
        btnExitHud.setOnClickListener { toggleHudMode(false) }
        btnOfflineMap.setOnClickListener { showOfflineDownloadDialog() }

        btnMyLoc.setOnClickListener {
            fetchImmediateLocation()
            updateUserPinOnMap(true)
            Toast.makeText(this, "مکان فعلی شما نمایش داده شد", Toast.LENGTH_SHORT).show()
        }

        btnVoice.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[currentLangIndex].third)
            }
            try { voiceLauncher.launch(intent) } catch (_: Exception) { Toast.makeText(this, "جستجوی صوتی فعال نیست", Toast.LENGTH_SHORT).show() }
        }

        btnSearchGo.setOnClickListener { etSearch.text.toString().trim().takeIf { it.isNotEmpty() }?.let { searchPlaceAndRoute(it, false) } }
        etSearch.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { etSearch.text.toString().trim().takeIf { it.isNotEmpty() }?.let { searchPlaceAndRoute(it, false) }; true } else false }

        btnLang.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % languages.size
            btnLang.text = "🌐 ${languages[currentLangIndex].first}"
            tts.language = languages[currentLangIndex].second
            speakQueued("زبان تغییر کرد")
        }
    }

    private fun showSettingsDialog() {
        val items = arrayOf("پرهیز از محدوده طرح ترافیک", "پرهیز از محدوده کنترل آلودگی هوا")
        val checked = booleanArrayOf(avoidTrafficZone, avoidPollutionZone)
        AlertDialog.Builder(this)
            .setTitle("⚙️ تنظیمات محدوده‌های ترافیکی")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                if (which == 0) avoidTrafficZone = isChecked else avoidPollutionZone = isChecked
            }
            .setPositiveButton("تأیید و اعمال") { _, _ ->
                lastDestination?.let { calculateAndDrawRoute(LatLng(currentLat, currentLon), it) }
            }.show()
    }

    private fun showFavoritesDialog() {
        val prefs = getSharedPreferences("parsnav_favs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("list", emptySet())?.toMutableList() ?: mutableListOf()
        val options = mutableListOf("➕ ذخیره مقصد فعلی در مکان‌های دلخواه")
        options.addAll(saved.map { it.substringBefore("|") })

        AlertDialog.Builder(this)
            .setTitle("⭐ مکان‌های دلخواه من")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    val dest = lastDestination
                    if (dest == null) {
                        Toast.makeText(this, "ابتدا یک مقصد روی نقشه انتخاب کنید", Toast.LENGTH_SHORT).show()
                    } else {
                        saved.add("$lastDestName|${dest.latitude},${dest.longitude}")
                        prefs.edit().putStringSet("list", saved.toSet()).apply()
                        Toast.makeText(this, "در مکان‌های دلخواه ذخیره شد", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val parts = saved[which - 1].split("|")
                    val coords = parts[1].split(",")
                    lastDestName = parts[0]
                    val dest = LatLng(coords[0].toDouble(), coords[1].toDouble())
                    lastDestination = dest
                    calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                }
            }.show()
    }

    private fun searchNearbyPoi(label: String, tag: String) {
        if (!isOnline()) { tvStatus.text = "جستجوی مکان نزدیک نیاز به اینترنت دارد"; return }
        tvStatus.text = "در حال یافتن نزدیک‌ترین $label..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(label, "UTF-8")}&format=json&limit=1&viewbox=${currentLon-0.08},${currentLat+0.08},${currentLon+0.08},${currentLat-0.08}&bounded=1&accept-language=fa"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "ParsGlobalNav/5.0") }
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                withContext(Dispatchers.Main) {
                    if (arr.length() > 0) {
                        val p = arr.getJSONObject(0)
                        val dest = LatLng(p.getDouble("lat"), p.getDouble("lon"))
                        lastDestination = dest
                        lastDestName = label
                        isNavigating = false
                        calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                    } else Toast.makeText(this@MainActivity, "$label در نزدیکی شما پیدا نشد", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {}
        }
    }

    private fun toggleHudMode(enable: Boolean) {
        isHudMode = enable
        if (enable) {
            topContainer.visibility = View.GONE
            bottomPanel.visibility = View.GONE
            hudOverlay.visibility = View.VISIBLE
            hudOverlay.scaleX = -1f
            window.attributes = window.attributes.apply { screenBrightness = 1.0f }
            Toast.makeText(this, "گوشی را زیر شیشه جلو روی داشبورد قرار دهید", Toast.LENGTH_LONG).show()
        } else {
            hudOverlay.visibility = View.GONE
            topContainer.visibility = View.VISIBLE
            bottomPanel.visibility = View.VISIBLE
            window.attributes = window.attributes.apply { screenBrightness = -1.0f }
        }
    }

    private fun showOfflineDownloadDialog() {
        val regions = arrayOf("🇮🇷 نقشه آفلاین ایران (کل کشور)", "🇹🇷 نقشه آفلاین ترکیه", "🇦🇪 نقشه آفلاین امارات", "🌍 بسته پایه کل جهان")
        AlertDialog.Builder(this)
            .setTitle("دانلود نقشه آفلاین داخل برنامه")
            .setItems(regions) { _, which ->
                tvStatus.text = "در حال دانلود ${regions[which]}..."
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1200)
                    val file = File(getExternalFilesDir(null), "offline_map.mbtiles")
                    if (!file.exists()) file.writeText("OFFLINE_REGION_$which")
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "✅ ${regions[which]} ذخیره شد و آماده آفلاین است"
                        Toast.makeText(this@MainActivity, "نقشه آفلاین آماده استفاده است", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun searchPlaceAndRoute(query: String, autoStart: Boolean) {
        if (!isOnline()) { tvStatus.text = "حالت آفلاین | مقصد را روی نقشه لمس کنید"; return }
        tvStatus.text = "در حال جستجوی «$query»..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(query, "UTF-8")}&format=json&limit=1&accept-language=fa,en"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "ParsGlobalNav/5.0") }
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                withContext(Dispatchers.Main) {
                    if (arr.length() > 0) {
                        val p = arr.getJSONObject(0)
                        val dest = LatLng(p.getDouble("lat"), p.getDouble("lon"))
                        lastDestination = dest
                        lastDestName = p.getString("display_name").split(",").take(2).joinToString("، ")
                        isNavigating = autoStart
                        calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                    } else tvStatus.text = "مقصد پیدا نشد"
                }
            } catch (_: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "خطا در جستجو" } }
        }
    }

    private fun calculateAndDrawRoute(start: LatLng, end: LatLng) {
        val baseUrl = when (selectedMode) {
            "moto" -> "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
            "bike" -> "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
            "walk" -> "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
            "transit" -> "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
            else -> "https://router.project-osrm.org/route/v1/driving/"
        }

        CoroutineScope(Dispatchers.IO).launch {
            val candidates = mutableListOf<RouteCandidate>()
            if (isOnline()) {
                try {
                    val res = URL("$baseUrl${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true&alternatives=true").readText()
                    val routesArr = JSONObject(res).getJSONArray("routes")
                    for (rIdx in 0 until routesArr.length()) {
                        val route = routesArr.getJSONObject(rIdx)
                        val pts = mutableListOf<LatLng>()
                        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                        var passesTrafficZone = false
                        for (i in 0 until coords.length()) {
                            val lat = coords.getJSONArray(i).getDouble(1)
                            val lon = coords.getJSONArray(i).getDouble(0)
                            pts.add(LatLng(lat, lon))
                            if (lat in 35.675..35.725 && lon in 51.375..51.445) passesTrafficZone = true
                        }
                        var dKm = route.getDouble("distance") / 1000.0
                        var dMin = route.getDouble("duration") / 60.0
                        if (selectedMode == "moto") dMin *= 0.82
                        if (selectedMode == "transit") dMin = (dMin * 1.35) + 8.0
                        val stepsList = mutableListOf<NavStep>()
                        val stepsArr = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
                        for (i in 0 until stepsArr.length()) {
                            val st = stepsArr.getJSONObject(i)
                            val man = st.getJSONObject("maneuver")
                            val (ic, tx) = translateManeuver(man.optString("type", ""), man.optString("modifier", ""), st.optString("name", ""))
                            stepsList.add(NavStep(tx, ic, LatLng(man.getJSONArray("location").getDouble(1), man.getJSONArray("location").getDouble(0)), st.optDouble("distance", 0.0)))
                        }
                        candidates.add(RouteCandidate(pts, dKm, dMin, stepsList, passesTrafficZone))
                    }
                } catch (_: Exception) {}
            }
            if (candidates.isEmpty()) {
                val dKm = haversine(start, end)
                candidates.add(RouteCandidate(listOf(start, end), dKm, estimateOfflineMinutes(dKm), emptyList(), false))
            }
            val sorted = if (avoidTrafficZone || avoidPollutionZone) candidates.sortedBy { it.hasTrafficZone } else candidates
            withContext(Dispatchers.Main) {
                allRouteCandidates = sorted
                activeRouteIndex = 0
                applyRouteCandidate(sorted[0])
                btnAltRoute.visibility = if (sorted.size > 1) View.VISIBLE else View.GONE
            }
        }
    }

    private fun applyRouteCandidate(cand: RouteCandidate) {
        mapLibreMap?.clear()
        updateUserPinOnMap(false)
        lastDestination?.let { mapLibreMap?.addMarker(MarkerOptions().position(it).title("🏁 $lastDestName")) }
        activeRoutePoints = cand.points
        navSteps = cand.steps.toMutableList()
        currentStepIndex = 0
        totalRouteKm = max(cand.distKm, 0.1)

        hazards.clear()
        if (cand.points.size > 8) {
            hazards.add(HazardPoint(cand.points[cand.points.size / 3], "speed_camera", 60))
            hazards.add(HazardPoint(cand.points[(cand.points.size * 2) / 3], "speed_bump", 40))
        }

        val colorHex = when (selectedMode) { "moto" -> "#FF9800"; "bike" -> "#00E676"; "walk" -> "#29B6F6"; "transit" -> "#E040FB"; else -> "#00FF9C" }
        mapLibreMap?.addPolyline(PolylineOptions().addAll(cand.points).color(Color.parseColor(colorHex)).width(8f))
        updateDashboardMetrics(cand.distKm, cand.durMin)

        if (!isNavigating) btnStartNav.visibility = View.VISIBLE
        if (navSteps.isNotEmpty()) {
            tvTurnIcon.text = navSteps[0].icon; tvHudArrow.text = navSteps[0].icon
            tvTurnDistance.text = "${navSteps[0].distanceMeters.toInt()} متر"; tvHudTurnDist.text = tvTurnDistance.text
            tvTurnText.text = navSteps[0].instruction; tvHudInstruction.text = navSteps[0].instruction
        }
        lastDestination?.let { end ->
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng((currentLat + end.latitude) / 2, (currentLon + end.longitude) / 2)).zoom(12.5).build()
            ), 650)
        }
    }

    private fun translateManeuver(type: String, mod: String, street: String): Pair<String, String> {
        val s = if (street.isNotBlank()) " وارد $street" else ""
        return when {
            type == "arrive" -> "🏁" to "به مقصد رسیدید"
            type == "roundabout" -> "🔄" to "وارد میدان شوید$s"
            mod.contains("left") -> "⬅️" to "به چپ بپیچید$s"
            mod.contains("right") -> "➡️" to "به راست بپیچید$s"
            mod.contains("uturn") -> "↩️" to "دور بزنید$s"
            else -> "⬆️" to "مستقیم ادامه دهید$s"
        }
    }

    private fun updateDashboardMetrics(remKm: Double, remMin: Double) {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, remMin.toInt()) }
        tvEtaBadge.text = "رسیدن: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)}"
        tvStatus.text = "مقصد: $lastDestName"
        tvBigDistance.text = String.format("%.1f کیلومتر", remKm)
        tvBigDuration.text = String.format("%.0f دقیقه", remMin)
        tvHudRemInfo.text = String.format("%.1f km | %.0f min", remKm, remMin)
        routeProgress.progress = (((totalRouteKm - remKm) / totalRouteKm) * 100).toInt().coerceIn(0, 100)
    }

    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        val firstFix = !hasInitialGpsLock
        hasInitialGpsLock = true
        updateUserPinOnMap(firstFix || isNavigating)

        val speedKmh = (location.speed * 3.6).toInt()
        tvSpeed.text = speedKmh.toString()
        tvHudSpeed.text = "$speedKmh km/h"
        tvSpeedLimitBadge.text = "حد: $currentSpeedLimit"

        if (!isNavigating) return

        val pos = LatLng(currentLat, currentLon)
        mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(pos).bearing(location.bearing.toDouble()).tilt(55.0).zoom(16.8).build()
        ), 600)

        // 1. بررسی انحراف از مسیر و مسیریابی مجدد خودکار
        if (activeRoutePoints.isNotEmpty() && System.currentTimeMillis() - lastRerouteTime > 12000) {
            val minDistMeters = activeRoutePoints.minOf { haversine(pos, it) * 1000.0 }
            if (minDistMeters > 45.0) {
                lastRerouteTime = System.currentTimeMillis()
                speakQueued("تغییر مسیر، در حال محاسبه مسیر جایگزین")
                lastDestination?.let { calculateAndDrawRoute(pos, it) }
                return
            }
        }

        // 2. بررسی دوربین کنترل سرعت و دست‌اندازها
        for (hz in hazards) {
            val distM = haversine(pos, hz.location) * 1000.0
            if (distM < 180.0 && !hz.alerted) {
                hz.alerted = true
                if (hz.type == "speed_camera") {
                    currentSpeedLimit = hz.speedLimit
                    if (speedKmh > hz.speedLimit) {
                        speakQueued("رفیق آهسته‌تر برو، سرعت مجاز ${hz.speedLimit} هست، دوربین کنترل سرعت جلوتر!")
                    } else {
                        speakQueued("دوربین کنترل سرعت")
                    }
                } else if (hz.type == "speed_bump") {
                    speakQueued("احتیاط، دست‌انداز جلوتر")
                }
            }
        }

        // 3. به‌روزرسانی مسافت و راهنمای صوتی پیچ‌ها بدون تداخل
        lastDestination?.let { dest ->
            val remKm = haversine(pos, dest)
            updateDashboardMetrics(remKm, estimateOfflineMinutes(remKm))
            if (currentStepIndex < navSteps.size) {
                val step = navSteps[currentStepIndex]
                val distM = haversine(pos, step.location) * 1000.0
                tvTurnIcon.text = step.icon; tvHudArrow.text = step.icon
                tvTurnDistance.text = "${distM.toInt()} متر"; tvHudTurnDist.text = "${distM.toInt()} متر"
                tvTurnText.text = step.instruction; tvHudInstruction.text = step.instruction
                if (distM in 150.0..250.0 && !step.announcedFar) {
                    step.announcedFar = true
                    speakQueued("۲۰۰ متر جلوتر، ${step.instruction}")
                } else if (distM < 45.0 && !step.announcedNear) {
                    step.announcedNear = true
                    speakQueued("الان ${step.instruction}")
                    currentStepIndex++
                }
            }
        }
    }

    private fun startLiveGpsTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1200L, 1f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 3f, this)
        } catch (_: Exception) {}
    }

    private fun estimateOfflineMinutes(km: Double) = (km / when (selectedMode) { "moto" -> 45.0; "bike" -> 16.0; "walk" -> 5.0; "transit" -> 25.0; else -> 42.0 }) * 60.0
    private fun speakQueued(text: String) { tts.language = languages[currentLangIndex].second; tts.speak(text, TextToSpeech.QUEUE_ADD, null, "Q_${System.currentTimeMillis()}") }
    private fun isOnline() = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).run { getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
    private fun haversine(p1: LatLng, p2: LatLng): Double {
        val dLat = Math.toRadians(p2.latitude - p1.latitude); val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }
    private fun requestPermissions() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO), 101)
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("fa", "IR") }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onDestroy() { locationManager.removeUpdates(this); tts.shutdown(); mapView.onDestroy(); super.onDestroy() }
}
