package org.hornecker.fuckfriends

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Check: Zugriff auf Nutzungsdaten
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val startTime = System.currentTimeMillis() - 1000 * 60
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTime, System.currentTimeMillis())
        val hasUsagePermission = stats.isNotEmpty()

        // 2. Check: Über anderen Apps einblenden
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (!hasUsagePermission) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } else if (!hasOverlayPermission) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(intent)
        } else {
            // Berechtigungen ok -> Führe zuerst den Firestore-Verbindungstest aus
            runFirestoreTest()

            // Überwachungsdienst starten
            val serviceIntent = Intent(this, ScreenTimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun runFirestoreTest() {
        val db = FirebaseFirestore.getInstance()
        val testData = hashMapOf(
            "test_key" to "connection_ok",
            "timestamp" to System.currentTimeMillis()
        )

        Log.d("FirestoreTest", "Starte Schreibtest...")

        db.collection("connection_test").document("test_doc")
            .set(testData)
            .addOnSuccessListener {
                Log.d("FirestoreTest", "SCHREIBEN ERFOLGREICH! Daten wurden in Firestore gespeichert.")

                // Direkt im Anschluss Lesetest ausführen
                db.collection("connection_test").document("test_doc")
                    .get()
                    .addOnSuccessListener { document ->
                        if (document != null && document.exists()) {
                            Log.d("FirestoreTest", "LESEN ERFOLGREICH! Daten aus Cloud: ${document.data}")
                        } else {
                            Log.e("FirestoreTest", "LESEN FEHLGESCHLAGEN: Dokument existiert nicht.")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirestoreTest", "LESEN FEHLGESCHLAGEN: ", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreTest", "SCHREIBEN FEHLGESCHLAGEN: ", e)
            }
    }
}