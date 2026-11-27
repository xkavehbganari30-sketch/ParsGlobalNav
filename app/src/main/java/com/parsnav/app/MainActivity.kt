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
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
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

    private lateinit var tvInstruction: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvEtaBadge: TextView
    private lateinit var tvTurnIcon: TextView
    private lateinit var tvTurnDistance: TextView
    private lateinit var tvTurnText: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var turnBanner: LinearLayout
    private lateinit var routeProgress: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var rootContainer: FrameLayout

    private var isHudMode = false
    private var isFriendlyPersona = true
    private var is3dFollowMode = true
    private var currentLangIndex = 0
    private var selectedMode = "car"
    private var lastDestination: LatLng? = null
    private var lastDestName: String = "مقصد"
    private var totalRouteKm: Double = 0.0
    private var navSteps = mutableListOf<NavStep>()
    private var currentStepIndex = 0
    private var lastSpeedAlertTime = 0L

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
                val spokenPlace = matches[0]
                etSearch.setText(spokenPlace)
                searchPlaceAndRoute(spokenPlace)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        tvInstruction = findViewById(R.id.tvInstruction)
        tvStatus = findViewById(R.id.tvStatus)
        tvEtaBadge = findViewById(R.id.tvEtaBadge)
        tvTurnIcon = findViewById(R.id.tvTurnIcon)
        tvTurnDistance = findViewById(R.id.tvTurnDistance)
        tvTurnText = findViewById(R.id.tvTurnText)
        tvSpeed = findViewById(R.id.tvSpeed)
        turnBanner = findViewById(R.id.turnBanner)
        routeProgress = findViewById(R.id.routeProgress)
        etSearch = findViewById(R.id.etSearch)
        rootContainer = findViewById(R.id.rootContainer)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        requestPermissions()
        setupMap()
        setupControls()
        startLiveGpsTracking()
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val offlineFile = File(getExternalFilesDir(null), "offline_map.mbtiles")

            if (isOnline()) {
                map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                tvStatus.text = "آنلاین | آماده مسیریابی زنده 3D"
            } else if (offlineFile.exists()) {
                val offlineStyle = """
                    {
                      "version": 8,
                      "sources": {
                        "offline-tiles": {
                          "type": "vector",
                          "url": "mbtiles://${offlineFile.absolutePath}"
                        }
                      },
                      "layers": [
                        { "id": "bg", "type": "background", "paint": { "background-color": "#161B22" } }
                      ]
                    }
                """.trimIndent()
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(offlineStyle))
                tvStatus.text = "آفلاین | نقشه داخلی فعال شد"
            } else {
                tvStatus.text = "حالت آفلاین | ردیابی GPS فعال"
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(currentLat, currentLon))
                .zoom(14.5)
                .tilt(45.0)
                .build()

            map.addOnMapClickListener { point ->
                lastDestination = point
                lastDestName = "نقطه انتخابی"
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
                updateModeUI()
                lastDestination?.let { dest ->
                    calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                }
            }
        }

        btnVoice.setOnClickListener { startVoiceRecognition() }

        btnSearchGo.setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isNotEmpty()) searchPlaceAndRoute(query)
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearch.text.toString().trim()
                if (query.isNotEmpty()) searchPlaceAndRoute(query)
                true
            } else false
        }

        btnPersona.setOnClickListener {
            isFriendlyPersona = !isFriendlyPersona
            btnPersona.text = if (isFriendlyPersona) "🎙️ لحن: صمیمی" else "🎙️ لحن: رسمی"
            val sample = if (isFriendlyPersona)
                "حالت صمیمی فعال شد! بزن بریم که راه بازه."
            else
                "دستیار صوتی روی حالت رسمی و دقیق تنظیم شد."
            speakText(sample)
        }

        btnLang.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % languages.size
            val selected = languages[currentLangIndex]
            btnLang.text = "🌐 ${selected.first}"
            tts.language = selected.second
            val msg = when (currentLangIndex) {
                0 -> "زبان فارسی فعال شد"
                1 -> "English voice guidance ready"
                2 -> "التوجيه الصوتي باللغة العربية"
                else -> "Türkçe sesli rehber hazır"
            }
            speakText(msg)
        }

        btnHud.setOnClickListener {
            isHudMode = !isHudMode
            rootContainer.scaleX = if (isHudMode) -1f else 1f
            Toast.makeText(this, if (isHudMode) "حالت HUD آینه‌ای فعال شد" else "حالت عادی", Toast.LENGTH_SHORT).show()
        }

        btnMyLoc.setOnClickListener {
            is3dFollowMode = !is3dFollowMode
            btnMyLoc.text = if (is3dFollowMode) "🧭 تعقیب 3D" else "🗺️ نمای آزاد"
            if (is3dFollowMode) {
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(currentLat, currentLon))
                            .zoom(16.0)
                            .tilt(50.0)
                            .build()
                    ), 800
                )
            }
        }
    }

    private fun updateModeUI() {
        modeButtons.forEach { (mode, btn) ->
            if (mode == selectedMode) {
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1F6FEB"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#21262D"))
                btn.setTextColor(Color.parseColor("#C9D1D9"))
            }
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[currentLangIndex].third)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "نام مقصد را بگویید...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "جستجوی صوتی روی گوشی فعال نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchPlaceAndRoute(query: String) {
        if (!isOnline()) {
            tvStatus.text = "حالت آفلاین | مقصد را مستقیماً روی نقشه لمس کنید"
            return
        }
        tvStatus.text = "در حال جستجوی «$query»..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val urlStr = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&accept-language=fa,en"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "ParsGlobalNav/3.0")
                val response = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(response)

                withContext(Dispatchers.Main) {
                    if (arr.length() > 0) {
                        val place = arr.getJSONObject(0)
                        val dest = LatLng(place.getDouble("lat"), place.getDouble("lon"))
                        lastDestination = dest
                        lastDestName = place.getString("display_name").split(",").take(2).joinToString("، ")
                        calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                    } else {
                        tvStatus.text = "مکانی با نام «$query» پیدا نشد"
                        speakText("مقصد مورد نظر پیدا نشد")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "خطا در جستجوی مقصد"
                }
            }
        }
    }

    private fun calculateAndDrawRoute(start: LatLng, end: LatLng) {
        mapLibreMap?.clear()
        mapLibreMap?.addMarker(MarkerOptions().position(end).title(lastDestName))
        navSteps.clear()
        currentStepIndex = 0

        val (routeColor, modeLabel, baseUrl) = when (selectedMode) {
            "moto" -> Triple("#FF9800", "موتورسیکلت", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")
            "bike" -> Triple("#00E676", "دوچرخه", "https://routing.openstreetmap.de/routed-bike/route/v1/driving/")
            "walk" -> Triple("#29B6F6", "پیاده‌روی", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/")
            "transit" -> Triple("#E040FB", "وسایل عمومی", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")
            else -> Triple("#00FF9C", "خودرو", "https://router.project-osrm.org/route/v1/driving/")
        }

        CoroutineScope(Dispatchers.IO).launch {
            val points = mutableListOf<LatLng>()
            var distanceKm = 0.0
            var durationMin = 0.0

            if (isOnline()) {
                try {
                    val urlStr = "$baseUrl${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson&steps=true"
                    val response = URL(urlStr).readText()
                    val json = JSONObject(response)
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    distanceKm = route.getDouble("distance") / 1000.0
                    durationMin = route.getDouble("duration") / 60.0

                    if (selectedMode == "moto") durationMin *= 0.82
                    if (selectedMode == "transit") durationMin = (durationMin * 1.35) + 8.0

                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    for (i in 0 until coords.length()) {
                        val c = coords.getJSONArray(i)
                        points.add(LatLng(c.getDouble(1), c.getDouble(0)))
                    }

                    val legs = route.getJSONArray("legs")
                    if (legs.length() > 0) {
                        val stepsArr = legs.getJSONObject(0).getJSONArray("steps")
                        for (i in 0 until stepsArr.length()) {
                            val st = stepsArr.getJSONObject(i)
                            val man = st.getJSONObject("maneuver")
                            val type = man.optString("type", "")
                            val modifier = man.optString("modifier", "")
                            val locArr = man.getJSONArray("location")
                            val streetName = st.optString("name", "")
                            val distM = st.optDouble("distance", 0.0)

                            val (icon, text) = translateManeuver(type, modifier, streetName)
                            navSteps.add(
                                NavStep(
                                    instruction = text,
                                    icon = icon,
                                    location = LatLng(locArr.getDouble(1), locArr.getDouble(0)),
                                    distanceMeters = distM
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    points.addAll(listOf(start, end))
                    distanceKm = haversine(start, end)
                    durationMin = estimateOfflineMinutes(distanceKm)
                }
            } else {
                points.addAll(listOf(start, end))
                distanceKm = haversine(start, end)
                durationMin = estimateOfflineMinutes(distanceKm)
            }

            totalRouteKm = max(distanceKm, 0.1)

            withContext(Dispatchers.Main) {
                mapLibreMap?.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(Color.parseColor(routeColor))
                        .width(8f)
                )

                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(start)
                            .zoom(15.5)
                            .tilt(50.0)
                            .build()
                    ), 900
                )

                updateDashboardMetrics(distanceKm, durationMin, modeLabel)

                if (navSteps.isNotEmpty()) {
                    turnBanner.visibility = View.VISIBLE
                    val firstStep = navSteps[0]
                    tvTurnIcon.text = firstStep.icon
                    tvTurnDistance.text = "${firstStep.distanceMeters.toInt()} متر"
                    tvTurnText.text = firstStep.instruction
                }

                announceRouteStart(distanceKm, durationMin, modeLabel)
            }
        }
    }

    private fun translateManeuver(type: String, modifier: String, street: String): Pair<String, String> {
        val s = if (street.isNotBlank()) " وارد $street" else ""
        return when {
            type == "arrive" -> "🏁" to "به مقصد رسیدید"
            type == "roundabout" -> "🔄" to "وارد میدان شوید$s"
            modifier.contains("sharp left") -> "↙️" to "گردش تند به چپ$s"
            modifier.contains("left") -> "⬅️" to "به چپ بپیچید$s"
            modifier.contains("sharp right") -> "↘️" to "گردش تند به راست$s"
            modifier.contains("right") -> "➡️" to "به راست بپیچید$s"
            modifier.contains("uturn") -> "↩️" to "دور بزنید$s"
            else -> "⬆️" to "مستقیم ادامه دهید$s"
        }
    }

    private fun updateDashboardMetrics(remKm: Double, remMin: Double, modeLabel: String) {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, remMin.toInt()) }
        tvEtaBadge.text = "رسیدن: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)}"
        tvStatus.text = "مقصد: $lastDestName ($modeLabel)"
        tvInstruction.text = String.format("%.1f کیلومتر  •  %.0f دقیقه باقی‌مانده", remKm, remMin)
        routeProgress.progress = (((totalRouteKm - remKm) / totalRouteKm) * 100).toInt().coerceIn(0, 100)
    }

    private fun announceRouteStart(distanceKm: Double, durationMin: Double, modeLabel: String) {
        val d = String.format("%.1f", distanceKm)
        val m = String.format("%.0f", durationMin)
        val next = if (navSteps.size > 1) navSteps[1].instruction else "مستقیم حرکت کنید"
        val msg = if (currentLangIndex == 0) {
            if (isFriendlyPersona) "مسیر $modeLabel به سمت $lastDestName آماده است! حدود $d کیلومتر و $m دقیقه تو راهیم. الان $next."
            else "مسیریابی $modeLabel به مقصد $lastDestName آغاز شد. مسافت $d کیلومتر، زمان تخمینی $m دقیقه. $next."
        } else "Navigation started. Distance $d kilometers, time $m minutes."
        speakText(msg)
    }

    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        val speedKmh = (location.speed * 3.6).toInt()
        tvSpeed.text = speedKmh.toString()

        if (speedKmh > 110 && System.currentTimeMillis() - lastSpeedAlertTime > 30000) {
            lastSpeedAlertTime = System.currentTimeMillis()
            tvSpeed.setTextColor(Color.RED)
            speakText(if (isFriendlyPersona) "سرعتت بالاست، احتیاط کن!" else "هشدار: سرعت بیش از حد مجاز.")
        } else if (speedKmh <= 110) tvSpeed.setTextColor(Color.parseColor("#00FF9C"))

        val pos = LatLng(currentLat, currentLon)
        if (is3dFollowMode) {
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(pos).bearing(location.bearing.toDouble()).tilt(52.0).zoom(16.5).build()
            ), 600)
        }

        lastDestination?.let { dest ->
            val remKm = haversine(pos, dest)
            updateDashboardMetrics(remKm, estimateOfflineMinutes(remKm), selectedMode)
            if (currentStepIndex < navSteps.size) {
                val step = navSteps[currentStepIndex]
                val distM = haversine(pos, step.location) * 1000.0
                tvTurnIcon.text = step.icon
                tvTurnDistance.text = "${distM.toInt()} متر"
                tvTurnText.text = step.instruction
                if (distM in 150.0..250.0 && !step.announcedFar) { step.announcedFar = true; speakText("۲۰۰ متر جلوتر، ${step.instruction}") }
                else if (distM < 45.0 && !step.announcedNear) { step.announcedNear = true; speakText("الان ${step.instruction}"); currentStepIndex++ }
            }
        }
    }

    private fun startLiveGpsTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this)
        } catch (_: Exception) {}
    }

    private fun estimateOfflineMinutes(km: Double) = (km / when (selectedMode) { "moto" -> 45.0; "bike" -> 16.0; "walk" -> 5.0; "transit" -> 25.0; else -> 42.0 }) * 60.0
    private fun speakText(text: String) { tts.language = languages[currentLangIndex].second; tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NAV_LIVE") }
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
