package com.example.smartblindstick

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class NavigationManager(private val context: Context) {

    fun startNavigation(destination: String) {
        // Create a Uri for Google Maps Navigation
        // Format: google.navigation:q=latitude,longitude OR google.navigation:q=Address
        val gmmIntentUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=w") // mode=w is for walking

        // Create an Intent from gmmIntentUri
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        
        // Make the Intent explicit by setting the Google Maps package
        mapIntent.setPackage("com.google.android.apps.maps")

        try {
            context.startActivity(mapIntent)
            Log.d("Navigation", "Started Google Maps navigation to $destination")
        } catch (e: Exception) {
            Log.e("Navigation", "Google Maps app is not installed or couldn't handle intent.")
            if (context is MainActivity) {
                context.speak("Google Maps is not installed on this device.")
            }
        }
    }
}
