package org.hornecker.fuckfriends

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.ListenerRegistration
import java.util.*
import kotlinx.coroutines.*

class ScreenTimeService : Service() {

    // Coroutine Scope, um schwere Systemabfragen vom Main-Thread fernzuhalten
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var limitInMinutes = 30 // Tageslimit für Instagram in Minuten

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var isOverlayShown = false

    private lateinit var repository: TimeRequestRepository
    private var myName: String = "Unbekannt"

    private var currentRequestId: String? = null
    private var requestListener: ListenerRegistration? = null
    private var openRequestsListener: ListenerRegistration? = null
    private val notifiedRequestIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = TimeRequestRepository(this)
        myName = DeviceIdentity.getDisplayName(this) ?: "Unbekannt"
        startForegroundServiceNotification()

        // Startet die asynchrone Überwachungsschleife
        startTrackingLoop()

        listenForFriendsRequests()
    }

    private fun startTrackingLoop() {
        serviceScope.launch {
            while (isActive) {
                // Diese beiden Aufrufe laufen jetzt sicher auf dem IO-Thread
                val currentUsage = getInstagramUsageTime(this@ScreenTimeService)
                val isInstagramInForeground = isAppInForeground("com.instagram.android")

                // UI-Änderungen (Overlay) müssen zwingend zurück auf den Main-Thread
                withContext(Dispatchers.Main) {
                    if (currentUsage >= limitInMinutes && isInstagramInForeground) {
                        showOverlay()
                    } else {
                        hideOverlay()
                    }
                }
                delay(2000) // Pausiert die Schleife ressourcenschonend für 2 Sekunden
            }
        }
    }

    private fun listenForFriendsRequests() {
        openRequestsListener = repository.listenToOpenRequestsFromOthers { requests ->
            for (request in requests) {
                if (request.id !in notifiedRequestIds) {
                    notifiedRequestIds.add(request.id)
                    showFriendRequestNotification(request)
                }
            }
        }
    }

    private fun showFriendRequestNotification(request: TimeRequest) {
        val channelId = "friend_requests_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Zeit-Anfragen von Freunden",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            request.id.hashCode(),
            openAppIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("${request.requestedByName} möchte mehr Zeit")
            .setContentText("${request.requestedMinutes} Minuten mehr – öffne die App zum Antworten")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(request.id.hashCode(), notification)
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
            text = "LIMIT ERREICHT!\nDeine $limitInMinutes Minuten sind um."
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val button = Button(context).apply {
            text = "Freunde um 15 Min. bitten"
            setOnClickListener {
                sendTimeRequest(this)
            }
        }

        layout.addView(textView)
        layout.addView(TextView(context).apply { height = 50 })
        layout.addView(button)

        overlayView = layout
        windowManager?.addView(layout, params)
        isOverlayShown = true
    }

    private fun hideOverlay() {
        if (!isOverlayShown) return
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
            isOverlayShown = false
        }
        requestListener?.remove()
        requestListener = null
        currentRequestId = null
    }

    private fun sendTimeRequest(button: Button) {
        if (currentRequestId != null) return

        button.isEnabled = false
        button.text = "Anfrage wird gesendet..."

        repository.createRequest(
            displayName = myName,
            requestedMinutes = 15,
            onSuccess = { requestId ->
                currentRequestId = requestId
                button.text = "Anfrage gesendet... Warte auf Antwort"
                listenForApproval(requestId, button)
            },
            onFailure = {
                button.isEnabled = true
                button.text = "Fehler – nochmal versuchen"
            }
        )
    }

    private fun listenForApproval(requestId: String, button: Button) {
        requestListener?.remove()
        requestListener = repository.listenToRequest(requestId) { status ->
            when (status) {
                "approved" -> {
                    limitInMinutes += 15
                    hideOverlay()
                }
                "denied" -> {
                    button.isEnabled = true
                    button.text = "Abgelehnt – nochmal fragen?"
                    currentRequestId = null
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
        serviceJob.cancel() // Beendet alle Coroutines sofort, um Leaks zu verhindern
        requestListener?.remove()
        openRequestsListener?.remove()
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