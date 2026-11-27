package com.parsnav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.net.URL
import java.util.Locale
import kotlin.math.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var tts: TextToSpeech
    private lateinit var tvInstruction: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rootContainer: FrameLayout

    private var isHudMode = false
    private var currentLangIndex = 0
    private val languages = listOf(
        Triple("فارسی", Locale("fa", "IR"), "مسیر محاسبه شد. به سمت مقصد حرکت کنید."),
        Triple("English", Locale.US, "Route calculated. Proceed to your destination."),
        Triple("العربية", Locale("ar", "SA"), "تم حساب المسار. توجه نحو وجهتك."),
        Triple("Türkçe", Locale("tr", "TR"), "Rota hesaplandı. Hedefinize doğru ilerleyin.")
    )

    // نقطه شروع پیش‌فرض: تهران (اولویت ایران) با پشتیبانی کل جهان
    private var currentLat = 35.6892
    private var currentLon = 51.3890

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        tvInstruction = findViewById(R.id.tvInstruction)
        tvStatus = findViewById(R.id.tvStatus)
        rootContainer = findViewById(R.id.rootContainer)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        requestLocationPermission()
        setupMap()
        setupButtons()
    }

    private fun setupMap() {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val offlineFile = File(getExternalFilesDir(null), "offline_map.mbtiles")

            if (isOnline()) {
                map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                tvStatus.text = "حالت آنلاین (نقشه جهانی فعال)"
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
                tvStatus.text = "حالت آفلاین (نقشه داخلی بارگذاری شد)"
            } else {
                tvStatus.text = "آفلاین | فایل offline_map.mbtiles در پوشه برنامه قرار دهید"
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(currentLat, currentLon))
                .zoom(12.0)
                .build()

            map.addOnMapClickListener { point ->
                calculateAndDrawRoute(LatLng(currentLat, currentLon), point)
                true
            }
        }
    }

    private fun calculateAndDrawRoute(start: LatLng, end: LatLng) {
        mapLibreMap?.clear()
        mapLibreMap?.addMarker(MarkerOptions().position(end).title("مقصد"))

        CoroutineScope(Dispatchers.IO).launch {
            val points = mutableListOf<LatLng>()
            var distanceKm = 0.0
            var durationMin = 0.0

            if (isOnline()) {
                try {
                    val urlStr = "https://router.project-osrm.org/route/v1/driving/" +
                            "${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=geojson"
                    val response = URL(urlStr).readText()
                    val json = JSONObject(response)
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    distanceKm = route.getDouble("distance") / 1000.0
                    durationMin = route.getDouble("duration") / 60.0

                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    for (i in 0 until coords.length()) {
                        val c = coords.getJSONArray(i)
                        points.add(LatLng(c.getDouble(1), c.getDouble(0)))
                    }
                } catch (e: Exception) {
                    points.addAll(listOf(start, end))
                    distanceKm = haversine(start, end)
                }
            } else {
                points.addAll(listOf(start, end))
                distanceKm = haversine(start, end)
                durationMin = (distanceKm / 40.0) * 60.0
            }

            withContext(Dispatchers.Main) {
                mapLibreMap?.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(Color.parseColor("#00FF9C"))
                        .width(6f)
                )
                val info = String.format("فاصله: %.1f کیلومتر | زمان: %.0f دقیقه", distanceKm, durationMin)
                tvInstruction.text = info
                speakGuidance(distanceKm)
            }
        }
    }

    private fun speakGuidance(distanceKm: Double) {
        val (_, locale, basePhrase) = languages[currentLangIndex]
        tts.language = locale
        val textToSpeak = when (currentLangIndex) {
            0 -> "فاصله تا مقصد ${String.format("%.1f", distanceKm)} کیلومتر است. $basePhrase"
            1 -> "Distance is ${String.format("%.1f", distanceKm)} kilometers. $basePhrase"
            2 -> "المسافة ${String.format("%.1f", distanceKm)} كيلومتر. $basePhrase"
            else -> "Mesafe ${String.format("%.1f", distanceKm)} kilometre. $basePhrase"
        }
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "NAV_ID")
    }

    private fun setupButtons() {
        val btnLang = findViewById<Button>(R.id.btnLang)
        val btnHud = findViewById<Button>(R.id.btnHud)
        val btnMyLoc = findViewById<Button>(R.id.btnMyLoc)

        btnLang.setOnClickListener {
            currentLangIndex = (currentLangIndex + 1) % languages.size
            val selected = languages[currentLangIndex]
            btnLang.text = "زبان: ${selected.first}"
            tts.language = selected.second
            tts.speak(selected.third, TextToSpeech.QUEUE_FLUSH, null, "LANG_TEST")
        }

        btnHud.setOnClickListener {
            isHudMode = !isHudMode
            rootContainer.scaleX = if (isHudMode) -1f else 1f
            Toast.makeText(this, if (isHudMode) "حالت HUD فعال شد" else "حالت عادی", Toast.LENGTH_SHORT).show()
        }

        btnMyLoc.setOnClickListener {
            updateUserLocation()
        }
    }

    private fun updateUserLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission()
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

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
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
