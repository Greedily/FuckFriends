package org.hornecker.fuckfriends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.ListenerRegistration

/**
 * Fragt beim allerersten Start den Anzeigenamen ab (z.B. "Eike").
 * Danach wird der Name lokal gespeichert und in Firestore registriert.
 */
@Composable
fun NameSetupScreen(onNameSet: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Wie heißt du?",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Dein Name wird deinen Freunden angezeigt, wenn du um mehr Zeit bittest.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (name.isNotBlank()) {
                    DeviceIdentity.setDisplayName(context, name.trim())
                    onNameSet(name.trim())
                }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Weiter")
        }
    }
}

/**
 * Zeigt alle offenen Zeit-Anfragen von Freunden (nicht die eigenen) an,
 * mit Buttons zum Genehmigen oder Ablehnen.
 */
@Composable
fun FriendRequestsScreen() {
    val context = LocalContext.current
    val myName = remember { DeviceIdentity.getDisplayName(context) ?: "Unbekannt" }
    val repository = remember { TimeRequestRepository(context) }

    var requests by remember { mutableStateOf<List<TimeRequest>>(emptyList()) }

    DisposableEffect(Unit) {
        val registration: ListenerRegistration = repository.listenToOpenRequestsFromOthers { newList ->
            requests = newList
        }
        onDispose { registration.remove() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Offene Anfragen deiner Freunde",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Aktuell keine offenen Anfragen. 🎉")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(requests, key = { it.id }) { request ->
                    RequestCard(
                        request = request,
                        onApprove = { repository.approveRequest(request.id, myName) },
                        onDeny = { repository.denyRequest(request.id, myName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestCard(
    request: TimeRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${request.requestedByName} möchte ${request.requestedMinutes} Minuten mehr",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) {
                    Text("Genehmigen")
                }
                OutlinedButton(onClick = onDeny) {
                    Text("Ablehnen")
                }
            }
        }
    }
}