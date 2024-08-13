package com.chaudhary.dev.mapradiuskotlin

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import model.LocationViewModel

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val locationViewModel: LocationViewModel by viewModels()
    private var googleMap: GoogleMap? = null
    private var locationMarker: Marker? = null
    private var circle: Circle? = null
    private var savedCameraPosition: CameraPosition? = null
    private var isInsideGeofence = true
    private var geofenceCenter: LatLng? = null
    private var hasPromptedForLocation = false
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
        private const val CAMERA_POSITION_KEY = "camera_position"
        private const val GEOFENCE_CENTER_KEY = "geofence_center"
        private const val IS_INSIDE_GEOFENCE_KEY = "is_inside_geofence"
        const val NOTIFICATION_CHANNEL_ID = "geofence_notification_channel"
        private const val GEOFENCE_RADIUS = 100f // Radius in meters
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionsResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.loading_indicator)
        progressBar.visibility = View.VISIBLE

        savedInstanceState?.let {
            restoreInstanceState(it)
        }

        requestPermissions()
        setupMapFragment()
        observeLocationChanges()
        checkLocationServices()
    }

    private fun checkLocationServices() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // Prompt user to enable location services
            promptForLocationServices()
        } else {
            // Start location updates
            startLocationUpdates()
        }
    }
    private fun promptForLocationServices() {
        if (!hasPromptedForLocation) {
            hasPromptedForLocation = true
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enable Location Services")
            builder.setMessage("Location services are required for this app. Please enable them in the settings.")
            builder.setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
            builder.setNegativeButton("Cancel", null)
            builder.show()
        }

    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveInstanceState(outState)
    }

    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
            .setMinUpdateIntervalMillis(500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.locations.forEach { location ->
                    locationViewModel.updateLocation(location)
                    geofenceCenter?.let { center ->
                        checkGeofenceTransition(location, center, GEOFENCE_RADIUS)
                    }
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    if (!hasPromptedForLocation) {
                        promptForLocationServices()
                    }
                } else {
                    hasPromptedForLocation = false
                    // Restart location updates if needed
                    startLocationUpdates()
                }
            }

        }

        if (isLocationPermissionGranted()) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        }
    }


    private fun observeLocationChanges() {
        locationViewModel.currentLocation.observe(this, Observer { location ->
            updateLocationOnMap(location)
        })
    }

    private fun updateLocationOnMap(location: Location) {
        googleMap?.let { map ->
            val latLng = LatLng(location.latitude, location.longitude)

            locationMarker?.let {
                it.position = latLng
            } ?: run {
                locationMarker = map.addMarker(
                    MarkerOptions().position(latLng).title(getString(R.string.you_are_here))
                )
            }

            circle?.let {
                map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            } ?: run {
                createGeofenceCircle(latLng, map)
            }
        } ?: Log.w("MainActivity", "GoogleMap is not ready yet!")
    }

    private fun createGeofenceCircle(latLng: LatLng, map: GoogleMap) {
        geofenceCenter = latLng

        val strokeColor = ContextCompat.getColor(this, R.color.purple_200)
        val fillColor = ContextCompat.getColor(this, R.color.purple_200) and 0x7F000000.toInt()

        circle = map.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(GEOFENCE_RADIUS.toDouble())
                .strokeColor(strokeColor)
                .fillColor(fillColor)
        )

        map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))

        progressBar.visibility = View.GONE
    }

    private fun checkGeofenceTransition(currentLocation: Location, geofenceCenter: LatLng, geofenceRadius: Float) {
        val distance = currentLocation.distanceTo(geofenceCenter.toLocation())
        if (distance > geofenceRadius && isInsideGeofence) {
            triggerExitNotification(geofenceCenter)
            isInsideGeofence = false
        } else if (distance <= geofenceRadius && !isInsideGeofence) {
            triggerEnterNotification(geofenceCenter)
            isInsideGeofence = true
        }
    }

    private fun triggerExitNotification(geofenceCenter: LatLng) {
        showNotification(
            getString(R.string.geofence_exit_title),
            getString(R.string.geofence_exit_message, geofenceCenter.latitude, geofenceCenter.longitude)
        )
    }

    private fun triggerEnterNotification(geofenceCenter: LatLng) {
        showNotification(
            getString(R.string.geofence_enter_title),
            getString(R.string.geofence_enter_message, geofenceCenter.latitude, geofenceCenter.longitude)
        )
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        savedCameraPosition?.let {
            googleMap?.moveCamera(CameraUpdateFactory.newCameraPosition(it))
        } ?: run {
            requestPermissions()
        }

        geofenceCenter?.let { center ->
            createGeofenceCircle(center, map)
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(center, savedCameraPosition?.zoom ?: 16f))
        }
    }

    private fun handlePermissionsResult(permissions: Map<String, Boolean>) {
        val locationPermissionGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val notificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        if (locationPermissionGranted) {
            startLocationUpdates()
        } else {
            Log.w("MainActivity", "Location permission denied.")
        }

        if (notificationPermissionGranted) {
            createNotificationChannel()
        } else {
            Log.w("MainActivity", "Notification permission denied.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.geofence_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.geofence_channel_description)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(0, notificationBuilder.build())
    }

    private fun saveInstanceState(outState: Bundle) {
        googleMap?.cameraPosition?.let {
            outState.putParcelable(CAMERA_POSITION_KEY, it)
        }
        geofenceCenter?.let {
            outState.putParcelable(GEOFENCE_CENTER_KEY, it)
        }
        outState.putBoolean(IS_INSIDE_GEOFENCE_KEY, isInsideGeofence)
    }

    private fun restoreInstanceState(savedInstanceState: Bundle) {
        savedCameraPosition = savedInstanceState.getParcelableCompat(CAMERA_POSITION_KEY)
        geofenceCenter = savedInstanceState.getParcelableCompat(GEOFENCE_CENTER_KEY)
        isInsideGeofence = savedInstanceState.getBoolean(IS_INSIDE_GEOFENCE_KEY, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }
    }

    private fun isLocationPermissionGranted() =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

// Extension function to convert LatLng to Location
private fun LatLng.toLocation(): Location {
    return Location("").apply {
        latitude = this@toLocation.latitude
        longitude = this@toLocation.longitude
    }
}
