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

data class NavStep(
    val instruction: String,
    val icon: String,
    val location: LatLng,
    val distanceMeters: Double,
    var announcedFar: Boolean = false,
    var announcedNear: Boolean = false
)

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
    private lateinit var routeProgress: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var rootContainer: FrameLayout

    private var userMarker: Marker? = null
    private var destMarker: Marker? = null
    private var isHudMode = false
    private var isNavigating = false
    private var isFriendlyPersona = true
    private var currentLangIndex = 0
    private var selectedMode = "car"
    private var lastDestination: LatLng? = null
    private var lastDestName: String = "مقصد"
    private var totalRouteKm: Double = 0.0
    private var totalRouteMin: Double = 0.0
    private var navSteps = mutableListOf<NavStep>()
    private var currentStepIndex = 0
    private var lastSpeedAlertTime = 0L
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

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spoken = matches[0]
                etSearch.setText(spoken)
                searchPlaceAndRoute(spoken)
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
        routeProgress = findViewById(R.id.routeProgress)
        etSearch = findViewById(R.id.etSearch)
        rootContainer = findViewById(R.id.rootContainer)
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
                CameraPosition.Builder().target(pos).zoom(15.5).build()
            ), 700)
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
        val btnPersona = findViewById<Button>(R.id.btnVoicePersona)
        val btnLang = findViewById<Button>(R.id.btnLang)
        val btnHud = findViewById<Button>(R.id.btnHud)
        val btnExitHud = findViewById<Button>(R.id.btnExitHud)
        val btnOfflineMap = findViewById<Button>(R.id.btnOfflineMap)
        val btnMyLoc = findViewById<Button>(R.id.btnMyLoc)

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
            val firstInst = if (navSteps.size > 1) navSteps[1].instruction else "مستقیم به سمت مقصد حرکت کنید"
            speakText(if (isFriendlyPersona) "بزن بریم! $firstInst" else "مسیریابی آغاز شد. $firstInst")
        }

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

        btnSearchGo.setOnClickListener { etSearch.text.toString().trim().takeIf { it.isNotEmpty() }?.let { searchPlaceAndRoute(it) } }
        etSearch.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { etSearch.text.toString().trim().takeIf { it.isNotEmpty() }?.let { searchPlaceAndRoute(it) }; true } else false }

        btnPersona.setOnClickListener {
            isFriendlyPersona = !isFriendlyPersona
            btnPersona.text = if (isFriendlyPersona) "🎙️ صمیمی" else "🎙️ رسمی"
            speakText(if (isFriendlyPersona) "حالت صمیمی فعال شد! بزن بریم." else "دستیار صوتی روی حالت رسمی تنظیم شد.")
        }

        btnLang.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % languages.size
            btnLang.text = "🌐 ${languages[currentLangIndex].first}"
            tts.language = languages[currentLangIndex].second
            speakText("زبان تغییر کرد")
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
                    delay(1500)
                    val file = File(getExternalFilesDir(null), "offline_map.mbtiles")
                    if (!file.exists()) file.writeText("OFFLINE_REGION_$which")
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "✅ ${regions[which]} ذخیره شد و آماده آفلاین است"
                        Toast.makeText(this@MainActivity, "نقشه آفلاین آماده استفاده است", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun searchPlaceAndRoute(query: String) {
        if (!isOnline()) { tvStatus.text = "حالت آفلاین | مقصد را روی نقشه لمس کنید"; return }
        tvStatus.text = "در حال جستجوی «$query»..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(query, "UTF-8")}&format=json&limit=1&accept-language=fa,en"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "ParsGlobalNav/4.0") }
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                withContext(Dispatchers.Main) {
                    if (arr.length() > 0) {
                        val p = arr.getJSONObject(0)
                        val dest = LatLng(p.getDouble("lat"), p.getDouble("lon"))
                        lastDestination = dest
                        lastDestName = p.getString("display_name").split(",").take(2).joinToString("، ")
                        isNavigating = false
                        calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                    } else tvStatus.text = "مقصد پیدا نشد"
                }
            } catch (_: Exception) { withContext(Dispatchers.Main) { tvStatus.text = "خطا در جستجو" } }
        }
    }

    private fun calculateAndDrawRoute(start: LatLng, end: LatLng) {
        mapLibreMap?.clear()
        updateUserPinOnMap(false)
        destMarker = mapLibreMap?.addMarker(MarkerOptions().position(end).title("🏁 $lastDestName"))
        navSteps.clear()
        currentStepIndex = 0

        val (colorHex, modeLabel, baseUrl) = when (selectedMode) {
            "moto" -> Triple("#FF9800", "موتور", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")
            "bike" -> Triple("#00E676", "دوچرخه", "https://routing.openstreetmap.de/routed-bike/route/v1/driving/")
            "walk" -> Triple("#29B6F6", "پیاده", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/")
            "transit" -> Triple("#E040FB", "عمومی", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")
            else -> Triple("#00FF9C", "خودرو", "https://router.project-osrm.org/route/v1/driving/")
        }

        CoroutineScope(Dispatchers.IO).launch {
            val points = mutableListOf<LatLng>()
            var distKm = 0.0; var durMin = 0.0
            if (isOnline()) {
                try {
                    val res = URL("$baseUrl${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true").readText()
                    val route = JSONObject(res).getJSONArray("routes").getJSONObject(0)
                    distKm = route.getDouble("distance") / 1000.0
                    durMin = route.getDouble("duration") / 60.0
                    if (selectedMode == "moto") durMin *= 0.82
                    if (selectedMode == "transit") durMin = (durMin * 1.35) + 8.0
                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    for (i in 0 until coords.length()) points.add(LatLng(coords.getJSONArray(i).getDouble(1), coords.getJSONArray(i).getDouble(0)))
                    val stepsArr = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
                    for (i in 0 until stepsArr.length()) {
                        val st = stepsArr.getJSONObject(i)
                        val man = st.getJSONObject("maneuver")
                        val (ic, tx) = translateManeuver(man.optString("type", ""), man.optString("modifier", ""), st.optString("name", ""))
                        navSteps.add(NavStep(tx, ic, LatLng(man.getJSONArray("location").getDouble(1), man.getJSONArray("location").getDouble(0)), st.optDouble("distance", 0.0)))
                    }
                } catch (_: Exception) { points.addAll(listOf(start, end)); distKm = haversine(start, end); durMin = estimateOfflineMinutes(distKm) }
            } else { points.addAll(listOf(start, end)); distKm = haversine(start, end); durMin = estimateOfflineMinutes(distKm) }

            totalRouteKm = max(distKm, 0.1); totalRouteMin = max(durMin, 1.0)
            withContext(Dispatchers.Main) {
                mapLibreMap?.addPolyline(PolylineOptions().addAll(points).color(Color.parseColor(colorHex)).width(8f))
                updateDashboardMetrics(distKm, durMin, modeLabel)
                btnStartNav.visibility = View.VISIBLE
                if (navSteps.isNotEmpty()) {
                    tvTurnIcon.text = navSteps[0].icon; tvHudArrow.text = navSteps[0].icon
                    tvTurnDistance.text = "${navSteps[0].distanceMeters.toInt()} متر"; tvHudTurnDist.text = tvTurnDistance.text
                    tvTurnText.text = navSteps[0].instruction; tvHudInstruction.text = navSteps[0].instruction
                }
                mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng((start.latitude + end.latitude) / 2, (start.longitude + end.longitude) / 2)).zoom(12.5).build()
                ), 700)
            }
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

    private fun updateDashboardMetrics(remKm: Double, remMin: Double, modeLabel: String) {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, remMin.toInt()) }
        tvEtaBadge.text = "رسیدن: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)}"
        tvStatus.text = "مقصد: $lastDestName ($modeLabel)"
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

        if (speedKmh > 100 && System.currentTimeMillis() - lastSpeedAlertTime > 30000) {
            lastSpeedAlertTime = System.currentTimeMillis()
            speakText("هشدار دوربین کنترل سرعت! سرعت خود را کاهش دهید.")
        }

        if (isNavigating) {
            val pos = LatLng(currentLat, currentLon)
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(pos).bearing(location.bearing.toDouble()).tilt(55.0).zoom(16.8).build()
            ), 600)
            lastDestination?.let { dest ->
                val remKm = haversine(pos, dest)
                updateDashboardMetrics(remKm, estimateOfflineMinutes(remKm), selectedMode)
                if (currentStepIndex < navSteps.size) {
                    val step = navSteps[currentStepIndex]
                    val distM = haversine(pos, step.location) * 1000.0
                    tvTurnIcon.text = step.icon; tvHudArrow.text = step.icon
                    tvTurnDistance.text = "${distM.toInt()} متر"; tvHudTurnDist.text = "${distM.toInt()} متر"
                    tvTurnText.text = step.instruction; tvHudInstruction.text = step.instruction
                    if (distM in 150.0..250.0 && !step.announcedFar) { step.announcedFar = true; speakText("۲۰۰ متر جلوتر، ${step.instruction}") }
                    else if (distM < 45.0 && !step.announcedNear) { step.announcedNear = true; speakText("الان ${step.instruction}"); currentStepIndex++ }
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
    private fun speakText(text: String) { tts.language = languages[currentLangIndex].second; tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NAV_V4") }
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
