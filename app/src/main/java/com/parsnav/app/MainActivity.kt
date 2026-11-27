package com.parsnav.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var tts: TextToSpeech
    private lateinit var tvInstruction: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var rootContainer: FrameLayout

    private var isHudMode = false
    private var currentLangIndex = 0
    private var selectedMode = "car"
    private var lastDestination: LatLng? = null
    private var lastDestName: String = "مقصد انتخابی"

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
        etSearch = findViewById(R.id.etSearch)
        rootContainer = findViewById(R.id.rootContainer)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        requestPermissions()
        setupMap()
        setupControls()
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val offlineFile = File(getExternalFilesDir(null), "offline_map.mbtiles")

            if (isOnline()) {
                map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                tvStatus.text = "آنلاین | نقشه جهانی آماده جستجوی صوتی"
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
                tvStatus.text = "آفلاین | نقشه داخلی بارگذاری شد"
            } else {
                tvStatus.text = "حالت آفلاین | آماده مسیریابی GPS"
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(currentLat, currentLon))
                .zoom(12.5)
                .build()

            map.addOnMapClickListener { point ->
                lastDestination = point
                lastDestName = "نقطه انتخابی روی نقشه"
                calculateAndDrawRoute(LatLng(currentLat, currentLon), point)
                true
            }
        }
    }

    private fun setupControls() {
        val btnVoice = findViewById<Button>(R.id.btnVoiceSearch)
        val btnSearchGo = findViewById<Button>(R.id.btnSearchGo)
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

        btnLang.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % languages.size
            val selected = languages[currentLangIndex]
            btnLang.text = "🌐 ${selected.first}"
            tts.language = selected.second
            val msg = when (currentLangIndex) {
                0 -> "زبان فارسی فعال شد"
                1 -> "English voice activated"
                2 -> "تم تفعيل اللغة العربية"
                else -> "Türkçe ses aktif"
            }
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "LANG_SW")
        }

        btnHud.setOnClickListener {
            isHudMode = !isHudMode
            rootContainer.scaleX = if (isHudMode) -1f else 1f
            Toast.makeText(this, if (isHudMode) "حالت HUD فعال شد" else "حالت عادی", Toast.LENGTH_SHORT).show()
        }

        btnMyLoc.setOnClickListener { updateUserLocation() }
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "نام مقصد را بگویید (مثلاً: میدان آزادی تهران)")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "جستجوی صوتی گوگل روی گوشی فعال نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchPlaceAndRoute(query: String) {
        if (!isOnline()) {
            tvStatus.text = "جستجوی نام مکان نیاز به اینترنت دارد (مقصد را روی نقشه لمس کنید)"
            return
        }
        tvStatus.text = "در حال جستجوی «$query»..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val urlStr = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&accept-language=fa,en"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "ParsGlobalNav/2.0")
                val response = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(response)

                withContext(Dispatchers.Main) {
                    if (arr.length() > 0) {
                        val place = arr.getJSONObject(0)
                        val lat = place.getDouble("lat")
                        val lon = place.getDouble("lon")
                        val displayName = place.getString("display_name").split(",").take(2).joinToString("، ")

                        val dest = LatLng(lat, lon)
                        lastDestination = dest
                        lastDestName = displayName

                        mapLibreMap?.cameraPosition = CameraPosition.Builder()
                            .target(dest)
                            .zoom(14.0)
                            .build()

                        calculateAndDrawRoute(LatLng(currentLat, currentLon), dest)
                    } else {
                        tvStatus.text = "مکانی با نام «$query» پیدا نشد"
                        tts.speak("مکانی پیدا نشد", TextToSpeech.QUEUE_FLUSH, null, "ERR")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "خطا در جستجو؛ اتصال اینترنت را بررسی کنید"
                }
            }
        }
    }

    private fun calculateAndDrawRoute(start: LatLng, end: LatLng) {
        mapLibreMap?.clear()
        mapLibreMap?.addMarker(MarkerOptions().position(end).title(lastDestName))

        val (routeColor, modeLabel, baseUrl) = when (selectedMode) {
            "moto" -> Triple("#FF9800", "موتورسیکلت", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")
            "bike" -> Triple("#00E676", "دوچرخه", "https://routing.openstreetmap.de/routed-bike/route/v1/driving/")
            "walk" -> Triple("#29B6F6", "پیاده‌روی", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/")
            "transit" -> Triple("#E040FB", "حمل‌ونقل عمومی", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")
            else -> Triple("#00FF9C", "خودرو", "https://router.project-osrm.org/route/v1/driving/")
        }

        CoroutineScope(Dispatchers.IO).launch {
            val points = mutableListOf<LatLng>()
            var distanceKm = 0.0
            var durationMin = 0.0

            if (isOnline()) {
                try {
                    val urlStr = "$baseUrl${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
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

            withContext(Dispatchers.Main) {
                mapLibreMap?.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(Color.parseColor(routeColor))
                        .width(7f)
                )
                tvStatus.text = "مقصد: $lastDestName | حالت: $modeLabel"
                val info = String.format("%.1f کیلومتر  •  %.0f دقیقه (%s)", distanceKm, durationMin, modeLabel)
                tvInstruction.text = info
                speakGuidance(distanceKm, durationMin, modeLabel)
            }
        }
    }

    private fun estimateOfflineMinutes(km: Double): Double {
        val speedKmh = when (selectedMode) {
            "moto" -> 45.0
            "bike" -> 16.0
            "walk" -> 5.0
            "transit" -> 25.0
            else -> 40.0
        }
        return (km / speedKmh) * 60.0
    }

    private fun speakGuidance(distanceKm: Double, durationMin: Double, modeLabel: String) {
        tts.language = languages[currentLangIndex].second
        val distStr = String.format("%.1f", distanceKm)
        val minStr = String.format("%.0f", durationMin)
        val textToSpeak = when (currentLangIndex) {
            0 -> "مسیر $modeLabel به سمت $lastDestName. فاصله $distStr کیلومتر، زمان تقریبی $minStr دقیقه."
            1 -> "Route to destination. Distance $distStr kilometers, estimated time $minStr minutes."
            2 -> "المسار إلى الوجهة. المسافة $distStr كيلومتر، الوقت المقدر $minStr دقيقة."
            else -> "Hedefe rota. Mesafe $distStr kilometre, tahmini süre $minStr dakika."
        }
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "NAV_V2")
    }

    private fun updateUserLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) {
            currentLat = loc.latitude
            currentLon = loc.longitude
            mapLibreMap?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(currentLat, currentLon))
                .zoom(15.0)
                .build()
            Toast.makeText(this, "موقعیت GPS به‌روزرسانی شد", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "در حال دریافت سیگنال GPS...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun haversine(p1: LatLng, p2: LatLng): Double {
        val r = 6371.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO),
            101
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("fa", "IR")
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onDestroy() { tts.shutdown(); mapView.onDestroy(); super.onDestroy() }
}
