package org.hornecker.fuckfriends

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Kapselt den Zugriff auf Firestore für:
 * - Geräte-Registrierung (devices/{deviceId})
 * - Zeit-Anfragen (time_requests/{autoId})
 *
 * Datenmodell "time_requests":
 * requestedByDeviceId: String   -> wer fragt
 * requestedByName: String        -> Anzeigename, z.B. "Eike"
 * requestedMinutes: Long
 * status: String                -> "pending" | "approved" | "denied"
 * timestamp: Long
 * respondedByName: String?      -> wer genehmigt/abgelehnt hat (optional, zur Info)
 */
class TimeRequestRepository(private val context: Context) {

    private val db = FirebaseFirestore.getInstance()
    private val devicesCollection = db.collection("devices")
    private val requestsCollection = db.collection("time_requests")

    private val myDeviceId = DeviceIdentity.getDeviceId(context)

    /** Registriert/aktualisiert dieses Gerät in Firestore, damit andere es "kennen". */
    fun registerDevice(displayName: String) {
        val data = hashMapOf(
            "deviceId" to myDeviceId,
            "displayName" to displayName,
            "lastSeen" to System.currentTimeMillis()
        )
        devicesCollection.document(myDeviceId).set(data)
            .addOnSuccessListener {
                Log.d("FirebaseDebug", "Gerät erfolgreich registriert: $myDeviceId")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseDebug", "Fehler bei Geräteregistrierung", e)
            }
    }

    /** Erstellt eine neue Zeit-Anfrage dieses Geräts. Gibt die neue Dokument-ID zurück (async über Callback). */
    fun createRequest(
        displayName: String,
        requestedMinutes: Long,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val request = hashMapOf(
            "requestedByDeviceId" to myDeviceId,
            "requestedByName" to displayName,
            "requestedMinutes" to requestedMinutes,
            "status" to "pending",
            "timestamp" to System.currentTimeMillis()
        )
        requestsCollection.add(request)
            .addOnSuccessListener { doc ->
                Log.d("FirebaseDebug", "Anfrage erstellt mit ID: ${doc.id}")
                onSuccess(doc.id)
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseDebug", "Fehler beim Erstellen der Anfrage", e)
                onFailure(e)
            }
    }

    /** Hört auf Statusänderungen einer eigenen, bereits gestellten Anfrage. */
    fun listenToRequest(
        requestId: String,
        onStatusChanged: (status: String) -> Unit
    ): ListenerRegistration {
        return requestsCollection.document(requestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirebaseDebug", "Fehler beim Beobachten der Anfrage $requestId: ${error.message}", error)
                    return@addSnapshotListener
                }
                val status = snapshot?.getString("status")
                if (status != null) onStatusChanged(status)
            }
    }

    /**
     * Hört auf ALLE offenen ("pending") Anfragen ANDERER Geräte.
     * WICHTIG: Erfordert einen zusammengesetzten Index (Composite Index) in Firebase!
     */
    fun listenToOpenRequestsFromOthers(
        onRequestsChanged: (List<TimeRequest>) -> Unit
    ): ListenerRegistration {
        return requestsCollection
            .whereEqualTo("status", "pending")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                // FEHLER-CHECK: Falls der Firestore-Index fehlt, wird hier der Link ausgegeben!
                if (error != null) {
                    Log.e("FirebaseDebug", "Fehler beim Laden der offenen Anfragen (Index fehlt?): ${error.message}", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    Log.d("FirebaseDebug", "Daten empfangen. Anzahl Dokumente: ${snapshot.size()}")
                }

                val list = snapshot?.documents
                    ?.mapNotNull { it.toTimeRequest() }
                    ?.filter { it.requestedByDeviceId != myDeviceId }
                    ?: emptyList()
                onRequestsChanged(list)
            }
    }

    /** Ein Freund genehmigt eine fremde Anfrage. */
    fun approveRequest(requestId: String, approverName: String) {
        requestsCollection.document(requestId)
            .update(
                mapOf(
                    "status" to "approved",
                    "respondedByName" to approverName
                )
            )
            .addOnFailureListener { e -> Log.e("FirebaseDebug", "Fehler beim Genehmigen", e) }
    }

    /** Ein Freund lehnt eine fremde Anfrage ab. */
    fun denyRequest(requestId: String, approverName: String) {
        requestsCollection.document(requestId)
            .update(
                mapOf(
                    "status" to "denied",
                    "respondedByName" to approverName
                )
            )
            .addOnFailureListener { e -> Log.e("FirebaseDebug", "Fehler beim Ablehnen", e) }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toTimeRequest(): TimeRequest? {
        val deviceId = getString("requestedByDeviceId") ?: return null
        val name = getString("requestedByName") ?: return null
        val minutes = getLong("requestedMinutes") ?: return null
        val status = getString("status") ?: return null
        val timestamp = getLong("timestamp") ?: 0L
        return TimeRequest(
            id = id,
            requestedByDeviceId = deviceId,
            requestedByName = name,
            requestedMinutes = minutes,
            status = status,
            timestamp = timestamp
        )
    }
}

data class TimeRequest(
    val id: String,
    val requestedByDeviceId: String,
    val requestedByName: String,
    val requestedMinutes: Long,
    val status: String,
    val timestamp: Long
)