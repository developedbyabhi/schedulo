package com.developwithabhi.schedulo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.developwithabhi.schedulo.core.Notifier
import com.developwithabhi.schedulo.core.Perms
import com.developwithabhi.schedulo.core.Scheduler
import com.developwithabhi.schedulo.core.WhatsApp
import com.developwithabhi.schedulo.data.AppDb
import com.developwithabhi.schedulo.data.Repeat
import com.developwithabhi.schedulo.data.ScheduledMessage
import com.developwithabhi.schedulo.data.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        setContent { MaterialTheme { App() } }
    }
}

private fun fmt(ms: Long): String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val flow = remember { dao.observeAll() }
    val messages by flow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var accOn by remember { mutableStateOf(true) }
    var exactOk by remember { mutableStateOf(true) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleResumeEffect(Unit) {
        accOn = Perms.accessibilityOn(ctx)
        exactOk = Perms.exactAlarmOk(ctx)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Schedulo") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Schedule") },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!accOn) item {
                SetupCard("Turn on auto-send", "Needed so Schedulo can tap Send in WhatsApp for you.", "Enable") {
                    showDisclosure = true
                }
            }
            if (!exactOk && Build.VERSION.SDK_INT >= 31) item {
                SetupCard("Allow exact timing", "So messages go out at the exact minute.", "Allow") {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }
            }
            if (messages.isEmpty()) item {
                Text("No scheduled messages yet. Tap Schedule to add one.", style = MaterialTheme.typography.bodyMedium)
            }
            items(messages, key = { it.id }) { m ->
                MessageCard(m) {
                    scope.launch(Dispatchers.IO) {
                        Scheduler.cancel(ctx, m.id)
                        dao.delete(m)
                    }
                }
            }
        }
    }

    // Prominent disclosure (required by Google Play before Accessibility use)
    if (showDisclosure) AlertDialog(
        onDismissRequest = { showDisclosure = false },
        title = { Text("Accessibility permission") },
        text = {
            Text(
                "Schedulo uses the Accessibility service only to open WhatsApp and tap Send for messages you schedule. " +
                    "It does not read, store or share your chats or any other screen content.\n\n" +
                    "On the next screen, open Schedulo and turn it on."
            )
        },
        confirmButton = {
            Button(onClick = {
                showDisclosure = false
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("I agree") }
        },
        dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("Not now") } },
    )

    if (showAdd) AddDialog(onDismiss = { showAdd = false }) { m ->
        scope.launch(Dispatchers.IO) {
            val id = dao.insert(m)
            Scheduler.schedule(ctx, m.copy(id = id))
        }
        showAdd = false
    }
}

@Composable
fun SetupCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun MessageCard(m: ScheduledMessage, onDelete: () -> Unit) {
    val repeatLabel = when (m.repeat) {
        Repeat.DAILY -> " · Daily"; Repeat.WEEKLY -> " · Weekly"; Repeat.MONTHLY -> " · Monthly"; else -> ""
    }
    val (statusText, statusColor) = when (m.status) {
        Status.SENT -> "Sent" to MaterialTheme.colorScheme.primary
        Status.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        else -> (if (m.askBeforeSend) "Scheduled · will ask" else "Scheduled") to MaterialTheme.colorScheme.secondary
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                Text(fmt(m.timeMillis) + repeatLabel, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(m.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

private fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(ctx, { _, y, mo, d ->
        TimePickerDialog(ctx, { _, h, mi ->
            c.set(y, mo, d, h, mi, 0)
            c.set(Calendar.MILLISECOND, 0)
            onPicked(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
    }.show()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddDialog(onDismiss: () -> Unit, onSave: (ScheduledMessage) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var app by remember {
        mutableStateOf(
            if (!WhatsApp.isInstalled(ctx, WhatsApp.PERSONAL) && WhatsApp.isInstalled(ctx, WhatsApp.BUSINESS))
                WhatsApp.BUSINESS else WhatsApp.PERSONAL
        )
    }
    var time by remember { mutableLongStateOf(System.currentTimeMillis() + 5 * 60_000) }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    var ask by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(uri, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                phone = c.getString(0) ?: ""
                if (name.isBlank()) name = c.getString(1) ?: ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Phone") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { contactPicker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }) {
                        Text("Contacts")
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                Text("Send via", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = app == WhatsApp.PERSONAL, onClick = { app = WhatsApp.PERSONAL }, label = { Text("WhatsApp") })
                    FilterChip(selected = app == WhatsApp.BUSINESS, onClick = { app = WhatsApp.BUSINESS }, label = { Text("Business") })
                }
                OutlinedButton(onClick = { pickDateTime(ctx, time) { time = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("When: ${fmt(time)}")
                }
                Text("Repeat", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Repeat.NONE to "Once", Repeat.DAILY to "Daily", Repeat.WEEKLY to "Weekly", Repeat.MONTHLY to "Monthly")
                        .forEach { (key, label) ->
                            FilterChip(selected = repeat == key, onClick = { repeat = key }, label = { Text(label) })
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask before sending")
                        Text("Get a notification and send it yourself", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = ask, onCheckedChange = { ask = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = WhatsApp.normalize(phone)
                error = when {
                    p.length < 11 -> "Enter a valid phone number"
                    text.isBlank() -> "Message can't be empty"
                    time <= System.currentTimeMillis() -> "Pick a future time"
                    !WhatsApp.isInstalled(ctx, app) -> "That WhatsApp app isn't installed"
                    else -> null
                }
                if (error == null) onSave(
                    ScheduledMessage(
                        name = name.ifBlank { "+$p" }, phone = p, text = text.trim(), app = app,
                        timeMillis = time, repeat = repeat, askBeforeSend = ask,
                    )
                )
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
