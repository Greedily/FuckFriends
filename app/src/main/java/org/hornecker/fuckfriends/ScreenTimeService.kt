package org.hornecker.fuckfriends

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class ScreenTimeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var limitInMinutes = 30 // Dein Limit. Da du bei 342 bist, schlägt es sofort an.

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var isOverlayShown = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            val currentUsage = getInstagramUsageTime(this@ScreenTimeService)
            val isInstagramInForeground = isAppInForeground("com.instagram.android")

            // Wir sperren NUR, wenn das Limit voll ist UND Instagram aktiv läuft
            if (currentUsage >= limitInMinutes && isInstagramInForeground) {
                showOverlay()
            } else {
                hideOverlay()
            }

            handler.postDelayed(this, 2000) // Alle 2 Sekunden prüfen
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()
        handler.post(checkRunnable)
    }

    private fun showOverlay() {
        if (isOverlayShown) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.RED)
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val textView = TextView(context).apply {
            text = "LIMIT ERREICHT!\nDeine 30 Minuten sind um."
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val button = Button(context).apply {
            text = "Kumpel um 15 Min. anflehen"
            setOnClickListener {
                sendTimeRequestToFirebase()
                text = "Anfrage gesendet... Warte auf Antwort"
                isEnabled = false
            }
        }

        layout.addView(textView)
        layout.addView(TextView(context).apply { height = 50 }) // Abstand
        layout.addView(button)

        overlayView = layout

        Handler(Looper.getMainLooper()).post {
            windowManager?.addView(layout, params)
            isOverlayShown = true
        }
    }

    private fun hideOverlay() {
        if (!isOverlayShown) return
        Handler(Looper.getMainLooper()).post {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
                isOverlayShown = false
            }
        }
    }

    private fun sendTimeRequestToFirebase() {
        val db = FirebaseFirestore.getInstance()

        val request = hashMapOf(
            "user" to "Eike",
            "status" to "pending",
            "requestedMinutes" to 15,
            "timestamp" to System.currentTimeMillis()
        )

        // Schreibt die Anfrage in die Firebase-Cloud
        db.collection("time_requests").document("anfrage_eike")
            .set(request)
            .addOnSuccessListener {
                println("Firebase: Anfrage hochgeladen!")
                listenForApproval()
            }
            .addOnFailureListener { e ->
                println("Firebase Fehler: ${e.message}")
            }
    }

    private fun listenForApproval() {
        val db = FirebaseFirestore.getInstance()

        // Hört live zu, ob sich der Status in der Cloud ändert
        db.collection("time_requests").document("anfrage_eike")
            .addSnapshotListener { snapshot, e ->
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")
                    if (status == "approved") {
                        limitInMinutes += 15 // Limit erhöhen
                        hideOverlay() // Sperre entfernen
                        println("Firebase: Freiheit gegönnt!")
                    }
                }
            }
    }

    private fun isAppInForeground(packageName: String): Boolean {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryEvents(time - 10000, time)
        val event = android.app.usage.UsageEvents.Event()
        var lastForegroundApp = ""

        while (stats.hasNextEvent()) {
            stats.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundApp = event.packageName
            }
        }
        return lastForegroundApp == packageName
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        hideOverlay()
        super.onDestroy()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "screen_time_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Auge fürs Limit", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Deine Freunde passen auf")
            .setContentText("Die App überwacht dein Instagram-Limit...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    private fun getInstagramUsageTime(context: Context): Long {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, calendar.timeInMillis, System.currentTimeMillis())
        var totalTimeMs: Long = 0
        stats?.forEach { if (it.packageName == "com.instagram.android") totalTimeMs += it.totalTimeInForeground }
        return totalTimeMs / 1000 / 60
    }
}