package com.example.galerinio.presentation.ui.fragment

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.databinding.FragmentGeoMapBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.charset.Charset
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GeoMapFragment : Fragment() {

    private data class GeoMediaPoint(
        val mediaId: Long,
        val isVideo: Boolean,
        val title: String,
        val subtitle: String,
        val latitude: Double,
        val longitude: Double
    )

    private data class GeoCacheEntry(
        val createdAtMs: Long,
        val points: List<GeoMediaPoint>
    )

    private data class ImportedGeoRow(
        val filePath: String?,

        val fileName: String?,

        val lat: Double,

        val lon: Double
    )

    private data class GeoScanStats(
        var imagesScanned: Int = 0,
        var imagesFromMediaStore: Int = 0,
        var imagesFromExif: Int = 0,
        var imagesFromXmp: Int = 0,
        var videosScanned: Int = 0,
        var videosFromMediaStore: Int = 0,
        var videosFromMetadata: Int = 0,
        var dbScanned: Int = 0,
        var dbFound: Int = 0,
        var importedMatched: Int = 0
    )

    private var _binding: FragmentGeoMapBinding? = null
    private val binding get() = _binding!!

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private var allPoints: List<GeoMediaPoint> = emptyList()
    private var filteredPoints: List<GeoMediaPoint> = emptyList()
    private var lastGeoScanStats = GeoScanStats()
    private var importedGeoRows: List<ImportedGeoRow> = emptyList()
    private var isViewActive = false
    private var mapCameraInitialized = false
    private var geoLoadJob: Job? = null
    private var forceFoundToast = false
    private var googleMap: GoogleMap? = null
    private val clusterMarkers = mutableListOf<Marker>()
    private val markerMediaMap = mutableMapOf<String, List<Long>>()

    private val csvImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val imported = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                parseCsvRows(text)
            } ?: emptyList()
        }.getOrElse { emptyList() }

        if (imported.isEmpty()) {
            Toast.makeText(requireContext(), getString(com.example.galerinio.R.string.geo_import_no_rows), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        importedGeoRows = imported
        saveImportedRowsToDisk(imported)
        geoCache = null
        invalidateGeoDiskCache()
        forceFoundToast = true
        Toast.makeText(requireContext(), getString(com.example.galerinio.R.string.geo_import_success, imported.size), Toast.LENGTH_LONG).show()
        checkPermissionsAndLoad()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it } || hasMediaReadPermission()) {
            loadLocations()
        } else {
            Toast.makeText(requireContext(), "Grant media access in system settings", Toast.LENGTH_LONG).show()
            renderEmpty()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeoMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isViewActive = true
        loadImportedRowsFromDisk()
        setupMapView()
        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (filteredPoints.isNotEmpty()) {
            renderCurrentPointsSafely()
        }
    }

    override fun onPause() {
        saveCurrentCameraState()
        super.onPause()
    }

    fun onMainPageSelected() {
        if (_binding == null) return
        var needReload = true
        if (filteredPoints.isNotEmpty()) {
            renderCurrentPointsSafely()
            needReload = false
        } else {
            val cachedPoints = getCachedPointsIfFresh()
            if (cachedPoints.isNotEmpty()) {
                allPoints = cachedPoints
                filteredPoints = cachedPoints
                renderCurrentPointsSafely()
                needReload = false
            }
        }
        if (needReload) {
            checkPermissionsAndLoad()
        }
    }

    fun forceMapRelayoutAndInvalidate() {
        if (_binding == null || !isViewActive) return
        if (filteredPoints.isNotEmpty()) {
            renderCurrentPointsSafely()
        }
    }

    fun refreshFromTopBar() {
        if (_binding == null) return
        geoCache = null
        invalidateGeoDiskCache()
        forceFoundToast = true
        checkPermissionsAndLoad()
    }

    override fun onDestroyView() {
        isViewActive = false
        clusterMarkers.clear()
        markerMediaMap.clear()
        googleMap = null
        super.onDestroyView()
        _binding = null
    }

    private fun setupMapView() {
        val mapFragment = childFragmentManager.findFragmentById(com.example.galerinio.R.id.mapView) as? SupportMapFragment
        mapFragment?.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMapToolbarEnabled = false

            map.setOnCameraIdleListener {
                if (filteredPoints.isNotEmpty()) {
                    renderPointsOnMap(filteredPoints)
                }
                saveCurrentCameraState()
            }

            map.setOnMarkerClickListener { marker ->
                val ids = markerMediaMap[marker.id]
                if (ids != null && ids.isNotEmpty()) {
                    openMediaCluster(ids)
                    true
                } else {
                    false
                }
            }

            restoreCameraOrDefault()

            if (filteredPoints.isNotEmpty()) {
                renderPointsOnMap(filteredPoints)
            }
        }
    }

    private fun restoreCameraOrDefault() {
        val map = googleMap ?: return
        val savedLat = lastMapCenterLat
        val savedLon = lastMapCenterLon
        val savedZoom = lastMapZoom
        if (savedLat != null && savedLon != null && savedZoom != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(savedLat, savedLon), savedZoom.toFloat()))
            mapCameraInitialized = true
            return
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.0, 15.0), 4f))
    }

    private fun saveCurrentCameraState() {
        val map = googleMap ?: return
        val pos = map.cameraPosition
        lastMapCenterLat = pos.target.latitude
        lastMapCenterLon = pos.target.longitude
        lastMapZoom = pos.zoom.toDouble()
    }

    private fun fitPointsOnFirstOpen(points: List<GeoMediaPoint>) {
        if (mapCameraInitialized || points.isEmpty()) return
        val map = googleMap ?: return

        if (points.size == 1) {
            val p = points.first()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 10f))
            mapCameraInitialized = true
            saveCurrentCameraState()
            return
        }

        val builder = LatLngBounds.builder()
        points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        val bounds = builder.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 72))
        mapCameraInitialized = true
        saveCurrentCameraState()
    }

    private fun checkPermissionsAndLoad() {
        if (hasMediaReadPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMediaLocationPermission()) {
                Toast.makeText(
                    requireContext(),
                    "Grant location metadata access for geotag map",
                    Toast.LENGTH_LONG
                ).show()
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
                return
            }
            loadLocations()
            return
        }
        permissionLauncher.launch(getMediaPermissionRequestList())
    }

    private fun loadLocations() {
        if (geoLoadJob?.isActive == true) return
        binding.progressGeo.visibility = View.VISIBLE
        binding.tvGeoEmpty.visibility = View.GONE

        geoLoadJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val points = runCatching {
                loadGeoPointsWithCache()
            }.getOrElse {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.progressGeo.visibility = View.GONE
                if (points.isEmpty()) {
                    Log.d(TAG, "Geo scan empty.")
                    renderEmpty()
                } else {
                    allPoints = points
                    filteredPoints = points
                    binding.tvGeoEmpty.visibility = View.GONE
                    binding.mapView.visibility = View.VISIBLE
                    val shouldShowFoundToast = forceFoundToast ||
                        lastAnnouncedGeoCount == null ||
                        lastAnnouncedGeoCount != points.size
                    if (shouldShowFoundToast) {
                        Toast.makeText(
                            requireContext(),
                            "Found ${points.size} geo file(s): P${lastGeoScanStats.imagesFromMediaStore + lastGeoScanStats.imagesFromExif + lastGeoScanStats.imagesFromXmp}, V${lastGeoScanStats.videosFromMediaStore + lastGeoScanStats.videosFromMetadata}, DB${lastGeoScanStats.dbFound}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    forceFoundToast = false
                    lastAnnouncedGeoCount = points.size
                    renderCurrentPointsSafely()
                }
            }
        }
        geoLoadJob?.invokeOnCompletion { geoLoadJob = null }
    }

    private suspend fun loadGeoPointsWithCache(): List<GeoMediaPoint> {
        val now = System.currentTimeMillis()
        val cached = geoCache
        if (cached != null && now - cached.createdAtMs < CACHE_TTL_MS) {
            return cached.points
        }

        val diskCached = loadGeoCacheFromDisk(now)
        if (diskCached != null) {
            geoCache = GeoCacheEntry(now, diskCached)
            return diskCached
        }

        lastGeoScanStats = GeoScanStats()
        var fresh = queryGeoPoints()
        if (fresh.isNotEmpty()) {
            fresh = filterPointsByExistingDbIds(fresh)
        }
        if (fresh.isEmpty()) {
            fresh = queryGeoPointsFromDatabase(lastGeoScanStats)
        }
        val importedPoints = queryImportedPointsFromDatabase(fresh.map { it.mediaId }.toSet(), lastGeoScanStats)
        if (importedPoints.isNotEmpty()) {
            fresh = (fresh + importedPoints).distinctBy { it.mediaId }
        }
        val entry = GeoCacheEntry(now, fresh)
        geoCache = entry
        saveGeoCacheToDisk(entry)
        return fresh
    }

    private fun getCachedPointsIfFresh(): List<GeoMediaPoint> {
        val now = System.currentTimeMillis()
        val mem = geoCache
        if (mem != null && now - mem.createdAtMs < CACHE_TTL_MS) {
            return mem.points
        }
        return loadGeoCacheFromDisk(now).orEmpty()
    }

    private fun loadGeoCacheFromDisk(now: Long): List<GeoMediaPoint>? {
        val file = File(requireContext().cacheDir, GEO_POINTS_CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText(Charset.forName("UTF-8")))
            val createdAt = root.optLong("createdAtMs", 0L)
            if (createdAt <= 0L || now - createdAt >= CACHE_TTL_MS) return null

            val arr = root.optJSONArray("points") ?: return null
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val lat = o.optDouble("latitude", Double.NaN)
                    val lon = o.optDouble("longitude", Double.NaN)
                    if (!isValidCoordinate(lat, lon)) continue
                    add(
                        GeoMediaPoint(
                            mediaId = o.optLong("mediaId", 0L),
                            isVideo = o.optBoolean("isVideo", false),
                            title = o.optString("title", ""),
                            subtitle = o.optString("subtitle", ""),
                            latitude = lat,
                            longitude = lon
                        )
                    )
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun saveGeoCacheToDisk(entry: GeoCacheEntry) {
        val file = File(requireContext().cacheDir, GEO_POINTS_CACHE_FILE)
        runCatching {
            val points = JSONArray()
            entry.points.forEach { point ->
                points.put(
                    JSONObject()
                        .put("mediaId", point.mediaId)
                        .put("isVideo", point.isVideo)
                        .put("title", point.title)
                        .put("subtitle", point.subtitle)
                        .put("latitude", point.latitude)
                        .put("longitude", point.longitude)
                )
            }

            val root = JSONObject()
                .put("createdAtMs", entry.createdAtMs)
                .put("points", points)
            file.writeText(root.toString(), Charset.forName("UTF-8"))
        }
    }

    private fun invalidateGeoDiskCache() {
        val file = File(requireContext().cacheDir, GEO_POINTS_CACHE_FILE)
        runCatching {
            if (file.exists()) file.delete()
        }
    }

    private suspend fun filterPointsByExistingDbIds(points: List<GeoMediaPoint>): List<GeoMediaPoint> {
        if (points.isEmpty()) return points
        val db = GalerioDatabase.getInstance(requireContext())
        val existingIds = db.mediaDao().getAllIds().toHashSet()
        return points.filter { it.mediaId in existingIds }
    }

    private suspend fun queryImportedPointsFromDatabase(
        existingIds: Set<Long>,
        stats: GeoScanStats
    ): List<GeoMediaPoint> {
        if (importedGeoRows.isEmpty()) return emptyList()
        val db = GalerioDatabase.getInstance(requireContext())
        val all = db.mediaDao().getAllMediaFlow().first()

        val byPath = importedGeoRows
            .mapNotNull { row -> row.filePath?.lowercase()?.let { key -> key to row } }
            .toMap()
        val byName = importedGeoRows
            .mapNotNull { row -> row.fileName?.lowercase()?.let { key -> key to row } }
            .toMap()

        val points = mutableListOf<GeoMediaPoint>()
        all.forEach { media ->
            if (media.id in existingIds) return@forEach
            val pathKey = media.filePath.lowercase()
            val nameKey = media.fileName.lowercase()
            val row = byPath[pathKey] ?: byName[nameKey] ?: return@forEach
            if (!isValidCoordinate(row.lat, row.lon)) return@forEach
            stats.importedMatched++
            points += GeoMediaPoint(
                mediaId = media.id,
                isVideo = media.mimeType.startsWith("video/"),
                title = media.fileName,
                subtitle = "Imported CSV",
                latitude = row.lat,
                longitude = row.lon
            )
        }
        return points
    }

    private fun renderCurrentPoints() {
        val bindingRef = _binding ?: return
        if (!isViewActive) return
        if (filteredPoints.isEmpty()) {
            renderEmpty()
            return
        }
        bindingRef.tvGeoEmpty.visibility = View.GONE
        bindingRef.mapView.visibility = View.VISIBLE
        renderPointsOnMap(filteredPoints)
    }

    private fun renderCurrentPointsSafely() {
        if (googleMap == null) return
        renderCurrentPoints()
    }

    private fun renderEmpty() {
        val bindingRef = _binding ?: return
        bindingRef.progressGeo.visibility = View.GONE
        bindingRef.mapView.visibility = View.GONE
        bindingRef.tvGeoEmpty.visibility = View.VISIBLE
    }

    private fun renderPointsOnMap(points: List<GeoMediaPoint>) {
        if (_binding == null || !isViewActive) return
        val map = googleMap ?: return

        val zoom = map.cameraPosition.zoom.toDouble()
        val cellDeg = clusterCellDegrees(zoom)
        val clusters = points.groupBy { point ->
            val latBucket = floor(point.latitude / cellDeg).toInt()
            val lonBucket = floor(point.longitude / cellDeg).toInt()
            "$latBucket:$lonBucket"
        }.values

        // Clear old markers
        clusterMarkers.forEach { it.remove() }
        clusterMarkers.clear()
        markerMediaMap.clear()

        if (clusters.isEmpty()) return

        clusters.forEach { bucket ->
            val centerLat = bucket.map { it.latitude }.average()
            val centerLon = bucket.map { it.longitude }.average()
            val count = bucket.size
            val mediaIds = bucket.map { it.mediaId }
            val previewPoint = bucket.first()
            val iconBitmap = createPreviewMarkerIcon(previewPoint, count)

            val markerOptions = MarkerOptions()
                .position(LatLng(centerLat, centerLon))
                .icon(BitmapDescriptorFactory.fromBitmap(iconBitmap))
                .anchor(0.5f, 1.0f)

            val marker = map.addMarker(markerOptions)
            if (marker != null) {
                clusterMarkers.add(marker)
                markerMediaMap[marker.id] = mediaIds
            }
        }

        fitPointsOnFirstOpen(points)
    }

    private fun clusterCellDegrees(zoom: Double): Double {
        return when {
            zoom < 4.5 -> 2.5
            zoom < 6.0 -> 1.2
            zoom < 8.0 -> 0.6
            zoom < 10.0 -> 0.3
            else -> 0.15
        }
    }

    private fun createPreviewMarkerIcon(point: GeoMediaPoint, count: Int): Bitmap {
        val size = 112
        val corner = 10f
        val badgeWidth = 42f
        val badgeHeight = 28f

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val rect = RectF(6f, 6f, size - 6f, size - 6f)

        val preview = loadMarkerPreviewBitmap(point, size)
        if (preview != null) {
            val saveLayer = canvas.saveLayer(rect, null)
            canvas.drawRoundRect(rect, corner, corner, clipPaint)
            clipPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(preview, null, rect, clipPaint)
            clipPaint.xfermode = null
            canvas.restoreToCount(saveLayer)
        } else {
            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55606F") }
            canvas.drawRoundRect(rect, corner, corner, fallbackPaint)
        }

        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        if (count > 1) {
            val badgeRect = RectF(2f, 2f, 2f + badgeWidth, 2f + badgeHeight)
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A5BD7") }
            canvas.drawRoundRect(badgeRect, 7f, 7f, badgePaint)

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val label = if (count > 999) "999+" else count.toString()
            val baseline = badgeRect.centerY() - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, badgeRect.centerX(), baseline, text)
        }

        return bitmap
    }

    private fun loadMarkerPreviewBitmap(point: GeoMediaPoint, targetSizePx: Int): Bitmap? {
        val mediaStoreId = kotlin.math.abs(point.mediaId)
        val contentUri = ContentUris.withAppendedId(
            if (point.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaStoreId
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                requireContext().contentResolver.loadThumbnail(contentUri, Size(targetSizePx, targetSizePx), null)
            }.getOrNull()
        }

        return runCatching {
            if (point.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(
                    requireContext().contentResolver,
                    mediaStoreId,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    BitmapFactory.Options()
                )
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    requireContext().contentResolver,
                    mediaStoreId,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    BitmapFactory.Options()
                )
            }
        }.getOrNull()
    }

    private fun openMediaCluster(mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        val fragment = GalleryFragment.newGeoClusterInstance(
            mediaIds = mediaIds.toLongArray(),
            title = if (mediaIds.size == 1) "Map item" else "Map cluster (${mediaIds.size})"
        )
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                com.example.galerinio.R.anim.slide_up_in,
                com.example.galerinio.R.anim.fade_out,
                com.example.galerinio.R.anim.fade_in,
                com.example.galerinio.R.anim.slide_down_out
            )
            .replace(com.example.galerinio.R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun queryGeoPoints(): List<GeoMediaPoint> {
        val points = mutableListOf<GeoMediaPoint>()
        points += queryImagesWithGeo(lastGeoScanStats)
        points += queryVideosWithGeo(lastGeoScanStats)
        return points.distinctBy { "${it.latitude}:${it.longitude}:${it.title}" }
    }

    private suspend fun queryGeoPointsFromDatabase(stats: GeoScanStats): List<GeoMediaPoint> {
        val database = GalerioDatabase.getInstance(requireContext())
        val all = database.mediaDao().getAllMediaFlow().first()
        val points = mutableListOf<GeoMediaPoint>()

        all.forEach { media ->
            stats.dbScanned++
            val path = media.filePath
            if (path.isBlank() || path.startsWith("content://")) return@forEach
            val file = File(path)
            if (!file.exists()) return@forEach

            val isVideo = media.id < 0 || media.mimeType.startsWith("video/")
            val geo = if (isVideo) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(path)
                    val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    retriever.release()
                    parseIso6709(location)
                }.getOrNull()
            } else {
                runCatching {
                    val exif = ExifInterface(path)
                    val latLong = FloatArray(2)
                    if (exif.getLatLong(latLong)) latLong[0].toDouble() to latLong[1].toDouble() else null
                }.getOrNull()
            }

            if (geo != null && isValidCoordinate(geo.first, geo.second)) {
                stats.dbFound++
                points += GeoMediaPoint(
                    mediaId = media.id,
                    isVideo = isVideo,
                    title = media.fileName,
                    subtitle = dateFormat.format(Date(media.dateAdded)),
                    latitude = geo.first,
                    longitude = geo.second
                )
            }
        }
        return points
    }

    private fun queryImagesWithGeo(stats: GeoScanStats): List<GeoMediaPoint> {
        val result = mutableListOf<GeoMediaPoint>()
        val projectionWithGeo = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            "latitude",
            "longitude"
        )
        val projectionBasic = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        imageContentUris().forEach { collectionUri ->
            val queriedWithGeo = runCatching {
                requireContext().contentResolver.query(
                    collectionUri,
                    projectionWithGeo,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
            }.getOrNull()

            val cursorToUse = queriedWithGeo ?: requireContext().contentResolver.query(
                collectionUri,
                projectionBasic,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursorToUse?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val latCol = cursor.getColumnIndex("latitude")
                val lonCol = cursor.getColumnIndex("longitude")

                while (cursor.moveToNext()) {
                    stats.imagesScanned++
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol).orEmpty()
                    val dateAddedSeconds = cursor.getLong(dateCol)
                    var lat = if (latCol >= 0) cursor.getDouble(latCol) else 0.0
                    var lon = if (lonCol >= 0) cursor.getDouble(lonCol) else 0.0

                    var source = 0
                    if (isValidCoordinate(lat, lon)) {
                        source = 1
                    }

                    if (source == 0) {
                        val imageUri = ContentUris.withAppendedId(collectionUri, id)
                        readExifLatLon(imageUri)?.let {
                            lat = it.first
                            lon = it.second
                            source = 2
                        }
                    }

                    if (source == 0) {
                        val imageUri = ContentUris.withAppendedId(collectionUri, id)
                        val absolutePath = resolveAbsolutePath(imageUri)
                        if (!absolutePath.isNullOrBlank()) {
                            readXmpSidecarLatLon(absolutePath)?.let {
                                lat = it.first
                                lon = it.second
                                source = 3
                            }
                        }
                    }

                    if (!isValidCoordinate(lat, lon)) continue
                    if (source == 1) stats.imagesFromMediaStore++
                    if (source == 2) stats.imagesFromExif++
                    if (source == 3) stats.imagesFromXmp++

                    result += GeoMediaPoint(
                        mediaId = id,
                        isVideo = false,
                        title = name,
                        subtitle = dateFormat.format(Date(dateAddedSeconds * 1000L)),
                        latitude = lat,
                        longitude = lon
                    )
                }
            }
        }
        return result
    }

    private fun queryVideosWithGeo(stats: GeoScanStats): List<GeoMediaPoint> {
        val result = mutableListOf<GeoMediaPoint>()
        val projectionWithGeo = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            "latitude",
            "longitude"
        )
        val projectionBasic = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED
        )

        videoContentUris().forEach { collectionUri ->
            val queriedWithGeo = runCatching {
                requireContext().contentResolver.query(
                    collectionUri,
                    projectionWithGeo,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )
            }.getOrNull()

            val cursorToUse = queriedWithGeo ?: requireContext().contentResolver.query(
                collectionUri,
                projectionBasic,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )

            cursorToUse?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val latCol = cursor.getColumnIndex("latitude")
                val lonCol = cursor.getColumnIndex("longitude")

                while (cursor.moveToNext()) {
                    stats.videosScanned++
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol).orEmpty()
                    val dateAddedSeconds = cursor.getLong(dateCol)
                    var lat = if (latCol >= 0) cursor.getDouble(latCol) else 0.0
                    var lon = if (lonCol >= 0) cursor.getDouble(lonCol) else 0.0

                    var source = 0
                    if (isValidCoordinate(lat, lon)) {
                        source = 1
                    }

                    if (source == 0) {
                        val videoUri = ContentUris.withAppendedId(collectionUri, id)
                        readVideoLatLon(videoUri)?.let {
                            lat = it.first
                            lon = it.second
                            source = 2
                        }
                    }

                    if (!isValidCoordinate(lat, lon)) continue
                    if (source == 1) stats.videosFromMediaStore++
                    if (source == 2) stats.videosFromMetadata++

                    result += GeoMediaPoint(
                        mediaId = -id,
                        isVideo = true,
                        title = name,
                        subtitle = dateFormat.format(Date(dateAddedSeconds * 1000L)),
                        latitude = lat,
                        longitude = lon
                    )
                }
            }
        }
        return result
    }

    private fun imageContentUris(): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        val volumes = MediaStore.getExternalVolumeNames(requireContext())
        if (volumes.isEmpty()) return listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        return volumes.map { MediaStore.Images.Media.getContentUri(it) }
    }

    private fun videoContentUris(): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }
        val volumes = MediaStore.getExternalVolumeNames(requireContext())
        if (volumes.isEmpty()) return listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        return volumes.map { MediaStore.Video.Media.getContentUri(it) }
    }

    private fun readVideoLatLon(videoUri: Uri): Pair<Double, Double>? {
        val byUri = runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(requireContext(), videoUri)
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            retriever.release()
            parseIso6709(location)
        }.getOrNull()
        if (byUri != null) return byUri

        val absolutePath = resolveAbsolutePath(videoUri)
        if (absolutePath.isNullOrBlank()) return null
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(absolutePath)
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            retriever.release()
            parseIso6709(location)
        }.getOrNull()
    }

    private fun parseIso6709(location: String?): Pair<Double, Double>? {
        if (location.isNullOrBlank()) return null
        val normalized = location.trim().removeSuffix("/")
        val match = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?).*").find(normalized) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        return if (isValidCoordinate(lat, lon)) lat to lon else null
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        return !(lat == 0.0 && lon == 0.0)
    }

    private fun readExifLatLon(imageUri: Uri): Pair<Double, Double>? {
        val preferredUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLocationPermission()) {
            runCatching { MediaStore.setRequireOriginal(imageUri) }.getOrDefault(imageUri)
        } else {
            imageUri
        }

        fun readFrom(uri: Uri): Pair<Double, Double>? {
            return runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    extractLatLonFromExif(exif)
                }
            }.getOrNull()
        }

        val fromUri = readFrom(preferredUri) ?: if (preferredUri != imageUri) readFrom(imageUri) else null
        if (fromUri != null) return fromUri

        val absolutePath = resolveAbsolutePath(imageUri)
        if (absolutePath.isNullOrBlank()) return null
        return runCatching {
            val exif = ExifInterface(absolutePath)
            extractLatLonFromExif(exif)
        }.getOrNull()
    }

    private fun extractLatLonFromExif(exif: ExifInterface): Pair<Double, Double>? {
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            val lat = latLong[0].toDouble()
            val lon = latLong[1].toDouble()
            if (isValidCoordinate(lat, lon)) return lat to lon
        }

        val rawLat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val rawLatRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
        val rawLon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
        val rawLonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
        val parsedLat = parseExifDms(rawLat, rawLatRef)
        val parsedLon = parseExifDms(rawLon, rawLonRef)
        return if (parsedLat != null && parsedLon != null && isValidCoordinate(parsedLat, parsedLon)) {
            parsedLat to parsedLon
        } else {
            null
        }
    }

    private fun parseExifDms(dms: String?, ref: String?): Double? {
        if (dms.isNullOrBlank()) return null
        val parts = dms.split(',')
        if (parts.size < 3) return null

        fun parseRational(value: String): Double? {
            val split = value.trim().split('/')
            if (split.size == 2) {
                val n = split[0].toDoubleOrNull() ?: return null
                val d = split[1].toDoubleOrNull() ?: return null
                if (d == 0.0) return null
                return n / d
            }
            return value.trim().toDoubleOrNull()
        }

        val deg = parseRational(parts[0]) ?: return null
        val min = parseRational(parts[1]) ?: return null
        val sec = parseRational(parts[2]) ?: return null
        var decimal = deg + (min / 60.0) + (sec / 3600.0)
        if (ref.equals("S", ignoreCase = true) || ref.equals("W", ignoreCase = true)) {
            decimal = -decimal
        }
        return decimal
    }

    private fun readXmpSidecarLatLon(imagePath: String): Pair<Double, Double>? {
        val imageFile = File(imagePath)
        if (!imageFile.exists()) return null
        val baseName = imageFile.nameWithoutExtension
        val dir = imageFile.parentFile ?: return null

        val xmpFile = File(dir, "$baseName.xmp").takeIf { it.exists() }
            ?: File(dir, "$baseName.XMP").takeIf { it.exists() }
            ?: return null

        val content = runCatching {
            xmpFile.readText(Charset.forName("UTF-8"))
        }.getOrNull() ?: return null

        val latRaw = extractXmpAttr(content, "exif:GPSLatitude") ?: extractXmpAttr(content, "xmp:GPSLatitude")
        val lonRaw = extractXmpAttr(content, "exif:GPSLongitude") ?: extractXmpAttr(content, "xmp:GPSLongitude")
        if (latRaw.isNullOrBlank() || lonRaw.isNullOrBlank()) return null

        val lat = parseFlexibleCoordinate(latRaw)
        val lon = parseFlexibleCoordinate(lonRaw)
        return if (lat != null && lon != null && isValidCoordinate(lat, lon)) lat to lon else null
    }

    private fun extractXmpAttr(xml: String, attrName: String): String? {
        val regex = Regex("""$attrName\s*=\s*\"([^\"]+)\""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun parseFlexibleCoordinate(value: String): Double? {
        val trimmed = value.trim()
        trimmed.toDoubleOrNull()?.let { return it }

        // Accept formats like "51,1234N" and "51.1234 W"
        val normalized = trimmed.replace(',', '.').replace(" ", "")
        val suffix = normalized.lastOrNull()?.uppercaseChar()
        val numericPart = if (suffix == 'N' || suffix == 'S' || suffix == 'E' || suffix == 'W') {
            normalized.dropLast(1)
        } else {
            normalized
        }
        val parsed = numericPart.toDoubleOrNull() ?: return null
        return when (suffix) {
            'S', 'W' -> -kotlin.math.abs(parsed)
            else -> parsed
        }
    }

    private fun parseCsvRows(csv: String): List<ImportedGeoRow> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()

        val delimiter = if (lines.first().contains(';')) ';' else ','
        val header = lines.first().split(delimiter).map { it.trim().lowercase() }
        val hasHeader = header.any { it.contains("lat") } && header.any { it.contains("lon") }

        val latIdx = if (hasHeader) header.indexOfFirst { it.contains("lat") } else 1
        val lonIdx = if (hasHeader) header.indexOfFirst { it.contains("lon") } else 2
        val pathIdx = if (hasHeader) header.indexOfFirst { it.contains("path") } else 0
        val nameIdx = if (hasHeader) header.indexOfFirst { it.contains("name") || it.contains("file") } else 0

        val start = if (hasHeader) 1 else 0
        val out = mutableListOf<ImportedGeoRow>()
        for (i in start until lines.size) {
            val cols = lines[i].split(delimiter).map { it.trim().trim('"') }
            if (latIdx !in cols.indices || lonIdx !in cols.indices) continue
            val lat = parseFlexibleCoordinate(cols[latIdx]) ?: continue
            val lon = parseFlexibleCoordinate(cols[lonIdx]) ?: continue
            if (!isValidCoordinate(lat, lon)) continue

            val rawPath = cols.getOrNull(pathIdx)?.takeIf { it.contains("/") || it.contains("\\") }
            val rawName = cols.getOrNull(nameIdx)?.takeIf { it.isNotBlank() }
            out += ImportedGeoRow(rawPath, rawName, lat, lon)
        }
        return out.distinctBy { "${it.filePath}|${it.fileName}|${it.lat}|${it.lon}" }
    }

    private fun loadImportedRowsFromDisk() {
        val file = File(requireContext().filesDir, IMPORT_FILE_NAME)
        if (!file.exists()) {
            importedGeoRows = emptyList()
            return
        }
        importedGeoRows = runCatching {
            val arr = JSONArray(file.readText(Charset.forName("UTF-8")))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val lat = o.optDouble("lat", Double.NaN)
                    val lon = o.optDouble("lon", Double.NaN)
                    if (!isValidCoordinate(lat, lon)) continue
                    add(
                        ImportedGeoRow(
                            filePath = o.optString("filePath").takeIf { it.isNotBlank() },
                            fileName = o.optString("fileName").takeIf { it.isNotBlank() },
                            lat = lat,
                            lon = lon
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun saveImportedRowsToDisk(rows: List<ImportedGeoRow>) {
        val arr = JSONArray()
        rows.forEach { row ->
            arr.put(
                JSONObject()
                    .put("filePath", row.filePath)
                    .put("fileName", row.fileName)
                    .put("lat", row.lat)
                    .put("lon", row.lon)
            )
        }
        val file = File(requireContext().filesDir, IMPORT_FILE_NAME)
        runCatching { file.writeText(arr.toString(), Charset.forName("UTF-8")) }
    }

    private fun resolveAbsolutePath(mediaUri: Uri): String? {
        return runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            requireContext().contentResolver.query(mediaUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)

                val dataPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                if (!dataPath.isNullOrBlank() && File(dataPath).exists()) {
                    return@use dataPath
                }

                val relPath = if (relIdx >= 0) cursor.getString(relIdx) else null
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                if (!relPath.isNullOrBlank() && !name.isNullOrBlank()) {
                    val normalized = relPath.removePrefix("/")
                    val full = "/storage/emulated/0/$normalized$name"
                    if (File(full).exists()) return@use full
                }
                null
            }
        }.getOrNull()
    }

    private fun hasMediaReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val hasVideos = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 34) {
                // On Android 14+, partial media access is valid and should not block map loading.
                hasImages || hasVideos || hasSelectedVisualPermission()
            } else {
                hasImages || hasVideos
            }
        } else {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasSelectedVisualPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return ContextCompat.checkSelfPermission(
            requireContext(),
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMediaLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getMediaPermissionRequestList(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private companion object {
        private const val TAG = "GeoMapFragment"
        private var geoCache: GeoCacheEntry? = null
        private var lastAnnouncedGeoCount: Int? = null
        private var lastMapCenterLat: Double? = null
        private var lastMapCenterLon: Double? = null
        private var lastMapZoom: Double? = null
        private const val GEO_POINTS_CACHE_FILE = "geo_points_cache_v1.json"
        private const val IMPORT_FILE_NAME = "geo_import_fallback.json"
        private const val CACHE_TTL_MS = 600_000L
    }
}
