package org.ncssar.rid2caltopo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.EditableRidMapping
import org.ncssar.rid2caltopo.data.RidMappingRules
import java.io.File
import org.ncssar.rid2caltopo.data.AircraftReadiness
import org.ncssar.rid2caltopo.data.AircraftOrganizationAccess
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RidMappingDraft(
    val key: Long,
    var remoteId: String,
    var ownerName: String,
    var ownerCallsign: String,
    var model: String,
    var readiness: AircraftReadiness = AircraftReadiness()
)

@Composable
fun RidMappingAdminDialog(
    onDismiss: () -> Unit,
    initialRemoteId: String? = null,
    onSaved: (String) -> Unit = {}
) {
    var canEdit by remember { mutableStateOf(AircraftOrganizationAccess.canEdit()) }
    var refreshingAccess by remember { mutableStateOf(false) }
    val accessChange by AircraftOrganizationAccess.changes.collectAsState()
    LaunchedEffect(accessChange) { canEdit = AircraftOrganizationAccess.canEdit() }
    suspend fun refreshAccess() {
        refreshingAccess = true
        try { canEdit = withContext(Dispatchers.IO) { AircraftOrganizationAccess.refresh() } }
        finally { refreshingAccess = false }
    }
    LaunchedEffect(Unit) { refreshAccess() }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var organization by remember { mutableStateOf(CaltopoClient.GetHomeOrgName()) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanTargetIndex by remember { mutableStateOf<Int?>(null) }
    var showScanChoices by remember { mutableStateOf(false) }
    var capturedPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var ocrCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextKey by remember { mutableStateOf(1L) }
    val mappings = remember {
        mutableStateListOf<RidMappingDraft>().also { drafts ->
            CaltopoClient.GetPersistedDroneSpecs().forEach { spec ->
                val ownerFields = RidMappingRules.resolveOwnerFields(
                    ownerName = spec.ownerName,
                    ownerCallsign = spec.owner,
                    legacyOwner = spec.owner,
                    mappedId = spec.mappedId,
                    model = spec.model,
                    remoteId = spec.remoteId
                )
                drafts += RidMappingDraft(
                    key = nextKey++,
                    remoteId = spec.remoteId,
                    ownerName = ownerFields.ownerName,
                    ownerCallsign = ownerFields.ownerCallsign,
                    model = spec.model,
                    readiness = spec.readiness
                )
            }
            initialRemoteId?.trim()?.takeIf { it.isNotEmpty() }?.let { remoteId ->
                val normalizedRemoteId = remoteId.uppercase()
                drafts += RidMappingDraft(
                    key = nextKey++,
                    remoteId = normalizedRemoteId,
                    ownerName = "",
                    ownerCallsign = "",
                    model = "",
                    readiness = AircraftReadiness(serialNumber = normalizedRemoteId)
                )
            }
        }
    }
    val initialKey = mappings.firstOrNull {
        initialRemoteId?.trim()?.equals(it.remoteId, ignoreCase = true) == true
    }?.key
    var selectedKey by remember(initialKey) { mutableStateOf(initialKey) }
    var baseline by remember { mutableStateOf(mappings.toList()) }
    var baselineOrganization by remember { mutableStateOf(organization) }
    fun beginEdit(key: Long) {
        baseline = mappings.toList()
        baselineOrganization = organization
        error = null
        selectedKey = key
    }
    fun cancelEdit() {
        mappings.clear()
        mappings.addAll(baseline)
        organization = baselineOrganization
        selectedKey = null
        scanTargetIndex = null
        error = null
    }
    suspend fun saveEdit(): Boolean {
        val index = mappings.indexOfFirst { it.key == selectedKey }
        val entry = mappings.getOrNull(index)
        val values = mappings.map { EditableRidMapping(it.remoteId, it.ownerName, it.ownerCallsign, it.model, it.readiness) }
        val errors = if (entry == null) emptyList() else RidMappingRules.validateEntry(organization, values[index], values.filterIndexed { i, _ -> i != index })
        if (errors.isNotEmpty()) { error = errors.joinToString("\n"); return false }
        saving = true
        try {
            if (!withContext(Dispatchers.IO) { AircraftOrganizationAccess.refresh() }) {
                error = "Editing permission could not be verified. Check your connection and organization administrator access, then try Save again. Your edits are retained."
                return false
            }
            val savedRemoteId = entry?.let { values[index].remoteId }
            return runCatching {
                if (entry != null) CaltopoClient.SavePersistedDroneSpec(organization, baseline.find { it.key == entry.key }?.remoteId, values[index])
                else baseline.find { it.key == selectedKey }?.let { CaltopoClient.RemovePersistedDroneSpec(it.remoteId) }
            }.fold(onSuccess = {
                    selectedKey = null
                    error = null
                    scanTargetIndex = null
                    savedRemoteId?.let(onSaved)
                    true
                },
                onFailure = { error = it.message; false })
        } finally { saving = false }
    }
    val barcodeScanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }
    val textRecognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    DisposableEffect(textRecognizer) {
        onDispose { textRecognizer.close() }
    }
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = capturedPhoto
        if (!captured || uri == null) return@rememberLauncherForActivityResult
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse {
                error = "Unable to read the serial-number photo."
                return@rememberLauncherForActivityResult
            }
        textRecognizer.process(image)
            .addOnSuccessListener { recognized ->
                val candidates = extractRemoteIdCandidates(recognized.text)
                if (candidates.isEmpty()) {
                    error = "No likely Remote ID was found. Move closer, improve lighting, and try again."
                } else {
                    ocrCandidates = candidates
                }
            }
            .addOnFailureListener {
                error = "Unable to recognize the printed Remote ID: ${it.localizedMessage ?: "unknown error"}"
            }
    }

    Dialog(
        onDismissRequest = { if (!saving) { if (selectedKey != null) cancelEdit() else onDismiss() } },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(enabled = selectedKey != null) { if (!saving) cancelEdit() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(if (selectedKey == null) "RID Map Entries" else "Aircraft details", style = MaterialTheme.typography.headlineSmall)
                    if (AircraftOrganizationAccess.belongsToOrganization()) {
                        Text("Organization account: " + (AircraftOrganizationAccess.organizationUser() ?: "Not verified"),
                            style = MaterialTheme.typography.titleMedium)
                        Text(AircraftOrganizationAccess.accessStatus(), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !refreshingAccess, onClick = { scope.launch { refreshAccess() } }) {
                            Text(if (refreshingAccess) "Checking access…" else "Refresh access")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(remember(selectedKey) { ScrollState(0) })
                    ) {
                        if (selectedKey != null) {
                        Text(
                            "Entries imported from the organization QR code can be reviewed or edited here. " +
                                "Organization is stored once and applied to every aircraft. " +
                                "Mapped ID is generated from owner callsign and model.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            enabled = false,
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization designator") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        }
                        mappings.forEachIndexed { index, draft ->
                            if (selectedKey == null) {
                                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { beginEdit(draft.key) }) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("${index + 1}.  ${draft.remoteId}", style = MaterialTheme.typography.titleSmall)
                                        Text("Owner: ${draft.ownerName.ifBlank { "—" }} · Model: ${draft.model}")
                                        Text("Designator: " + EditableRidMapping(draft.remoteId, draft.ownerName, draft.ownerCallsign, draft.model).mappedId(), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            } else if (selectedKey == draft.key) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    Text("Aircraft ${index + 1}", style = MaterialTheme.typography.titleSmall)
                                    OutlinedTextField(
                            enabled = canEdit && !saving,
                                        value = draft.remoteId,
                                        onValueChange = {
                                            val normalized = it.uppercase()
                                            val serial = draft.readiness.serialNumber.ifBlank { normalized }
                                            mappings[index] = draft.copy(
                                                remoteId = normalized,
                                                readiness = draft.readiness.copy(serialNumber = serial)
                                            )
                                        },
                                        label = { Text("Remote ID") },
                                        singleLine = true,
                                        trailingIcon = {
                                            IconButton(enabled = canEdit && !saving, onClick = {
                                                scanTargetIndex = index
                                                showScanChoices = true
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.DocumentScanner,
                                                    contentDescription = "Scan Remote ID"
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                            enabled = canEdit && !saving,
                                        value = draft.ownerName,
                                        onValueChange = { mappings[index] = draft.copy(ownerName = it) },
                                        label = { Text("Owner name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                            enabled = canEdit && !saving,
                                        value = draft.ownerCallsign,
                                        onValueChange = { mappings[index] = draft.copy(ownerCallsign = it) },
                                        label = { Text("Owner callsign (for example 1SAR7)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                            enabled = canEdit && !saving,
                                        value = draft.model,
                                        onValueChange = { mappings[index] = draft.copy(model = it) },
                                        label = { Text("Model") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    AircraftReadinessFields(draft.readiness, canEdit && !saving) { mappings[index] = draft.copy(readiness = it) }
                                    Text(
                                        "Drone designator: " + EditableRidMapping(
                                            draft.remoteId,
                                            draft.ownerName,
                                            draft.ownerCallsign,
                                            draft.model
                                        ).mappedId(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(enabled = canEdit && !saving, onClick = {
                                        scope.launch {
                                            val removed = mappings.removeAt(index)
                                            if (!saveEdit()) mappings.add(index, removed)
                                        }
                                    }) {
                                        Text("Remove aircraft")
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        }
                        if (selectedKey == null) {
                        if (mappings.isEmpty()) Text("No aircraft entries.")
                        OutlinedButton(
                            enabled = canEdit && !saving,
                            onClick = {
                                val draft = RidMappingDraft(nextKey++, "", "", "", "")
                                beginEdit(draft.key)
                                mappings += draft
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add aircraft") }
                        }
                        error?.let {
                            HorizontalDivider()
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedKey == null) {
                            TextButton(onClick = onDismiss) { Text("Close") }
                        } else {
                            TextButton(enabled = !saving, onClick = { cancelEdit() }) { Text(if (canEdit) "Cancel" else "Back") }
                            if (canEdit) Button(enabled = !saving, onClick = { scope.launch { saveEdit() } }) { Text(if (saving) "Checking permission…" else "Save") }
                        }
                    }
                }
            }
        }
    }

    if (showScanChoices) {
        AlertDialog(
            onDismissRequest = { showScanChoices = false },
            title = { Text("Scan Remote ID") },
            text = {
                Text("Scan a barcode when present, or photograph the printed alphanumeric serial number.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showScanChoices = false
                    barcodeScanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val candidate = barcode.rawValue
                                ?.let(::extractRemoteIdCandidates)
                                ?.firstOrNull()
                            val target = scanTargetIndex
                            if (candidate != null && target != null && target in mappings.indices && mappings[target].key == selectedKey && AircraftOrganizationAccess.canEdit()) {
                                mappings[target] = mappings[target].copy(remoteId = candidate)
                            } else {
                                error = "The barcode did not contain a recognizable Remote ID."
                            }
                        }
                }) { Text("Scan barcode") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScanChoices = false
                    val photo = runCatching {
                        File.createTempFile("rid_serial_", ".jpg", context.cacheDir)
                    }.getOrElse {
                        error = "Unable to prepare the camera."
                        return@TextButton
                    }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photo
                    )
                    capturedPhoto = uri
                    photoLauncher.launch(uri)
                }) { Text("Read printed text") }
            }
        )
    }

    if (ocrCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { ocrCandidates = emptyList() },
            title = { Text("Confirm Remote ID") },
            text = {
                Column {
                    Text("Select the exact value and verify ambiguous characters such as 0/O and 1/I.")
                    Spacer(Modifier.height(8.dp))
                    ocrCandidates.take(5).forEach { candidate ->
                        TextButton(
                            onClick = {
                                val target = scanTargetIndex
                                if (target != null && target in mappings.indices && mappings[target].key == selectedKey && AircraftOrganizationAccess.canEdit()) {
                                    mappings[target] = mappings[target].copy(remoteId = candidate)
                                }
                                ocrCandidates = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(candidate)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ocrCandidates = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun extractRemoteIdCandidates(value: String): List<String> {
    val ignored = setOf("SERIAL", "NUMBER", "REMOTEID", "CREDENTIAL")
    return Regex("[A-Za-z0-9]{8,24}")
        .findAll(value)
        .map { it.value.uppercase() }
        .filterNot { it in ignored }
        .distinct()
        .sortedWith(
            compareByDescending<String> { it.length == 20 }
                .thenByDescending { it.length }
        )
        .toList()
}

@Composable
fun OrganizationUserLabel() {
    val change by AircraftOrganizationAccess.changes.collectAsState()
    val endpoint = CaltopoClient.GetTrackerCoordinationUrlPfx()
    val credential = CaltopoClient.GetTrackerCoordinationApiKey()
    LaunchedEffect(endpoint, credential) { withContext(Dispatchers.IO) { AircraftOrganizationAccess.refresh() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, endpoint, credential) {
        // Browser sign-in changes server authorization without changing the device token.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { withContext(Dispatchers.IO) { AircraftOrganizationAccess.refresh() } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val username = remember(change, endpoint, credential) { AircraftOrganizationAccess.organizationUser() }
    if (AircraftOrganizationAccess.belongsToOrganization()) {
        Text("Organization account: " + (username ?: "Not verified"), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(6.dp))
    }
}
