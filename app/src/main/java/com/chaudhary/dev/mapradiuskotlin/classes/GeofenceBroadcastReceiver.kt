package com.chaudhary.dev.mapradiuskotlin.classes

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaudhary.dev.mapradiuskotlin.MainActivity
import com.chaudhary.dev.mapradiuskotlin.R
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent?.hasError() == true) {
            Log.e("GeofenceBroadcastReceiver", "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent?.geofenceTransition
        Log.d("GeofenceBroadcastReceiver", "Geofence transition: $geofenceTransition")

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            val triggeringGeofences = geofencingEvent.triggeringGeofences
            val geofenceDetails = triggeringGeofences?.joinToString { it.requestId }
            Log.d("GeofenceBroadcastReceiver", "Triggered geofences: $geofenceDetails")
            sendNotification(context, geofenceDetails)
        } else {
            Log.d("GeofenceBroadcastReceiver", "No geofence transition detected.")
        }
    }

    private fun sendNotification(context: Context, geofenceDetails: String?) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationBuilder = NotificationCompat.Builder(context, MainActivity.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Geofence Exit")
            .setContentText("You have exited the geofence: $geofenceDetails")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // Trigger the notification
        notificationManager.notify(0, notificationBuilder.build())
    }
}
