package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.ui.AlertDialog

@Composable fun DjiCloudSetupDialog(onDismiss:()->Unit,onSaved:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var appId by remember{mutableStateOf("")};var appKey by remember{mutableStateOf("")};var license by remember{mutableStateOf("")}
    var hostname by remember{mutableStateOf("")}
    var configured by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(true)}
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) {
            DjiCloudCredentialStore.configured(context) to DjiCloudTlsStore.importedHost(context)
        }
        configured = saved.first; hostname = saved.second; loading = false
    }
    var error by remember{mutableStateOf<String?>(null)};var status by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val certificate=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){busy=true;error=null;scope.launch{
        runCatching{withContext(Dispatchers.IO){
            val pem=context.contentResolver.openInputStream(uri)?.use{input->
                val buffer=java.io.ByteArrayOutputStream();val block=ByteArray(8192)
                while(true){val count=input.read(block);if(count<0)break;require(buffer.size()+count<=128*1024){"Certificate bundle is too large."};buffer.write(block,0,count)}
                buffer.toString("UTF-8")
            } ?: throw IllegalArgumentException("Cannot read the certificate bundle.")
            DjiCloudTlsStore.import(context,hostname.trim(),pem)
        }}.onSuccess{status="Certificate saved securely on this tablet."}.onFailure{error=it.message?:"Certificate import failed."};busy=false
    }}}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Pilot 2 connection setup")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("DJI app registration is included with R2C. These fields are for administrator overrides, encrypted with this tablet's Android Keystore and excluded from backup and mission exports.")
            if(configured)Text("Cloud API credentials are already saved. Leave these fields empty to retain them.")
            OutlinedTextField(appId,{appId=it},label={Text("Cloud API app ID")},singleLine=true,enabled=!busy&&!loading,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(appKey,{appKey=it},label={Text("Cloud API app key")},singleLine=true,enabled=!busy&&!loading,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(license,{license=it},label={Text("App basic license")},enabled=!busy&&!loading,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Text("Optional: import a dedicated tablet HTTPS certificate. Its hostname must resolve to this tablet on the incident LAN. Native Pilot 2 clients may reject the temporary test certificate.")
            OutlinedTextField(hostname,{hostname=it},label={Text("Certificate hostname")},singleLine=true,enabled=!busy&&!loading,modifier=Modifier.fillMaxWidth())
            TextButton(enabled=!busy&&!loading&&hostname.isNotBlank(),onClick={certificate.launch(arrayOf("application/x-pem-file","application/octet-stream","text/plain"))}){Text("Import PEM certificate chain + PKCS8 key")}
            Text(status);error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(enabled=!busy&&!loading,onClick={busy=true;error=null;scope.launch{
        runCatching{withContext(Dispatchers.IO){if(appId.isNotBlank()||appKey.isNotBlank()||license.isNotBlank())DjiCloudCredentialStore.save(context,DjiCloudCredentials(appId.trim(),appKey.trim(),license.trim()))
            require(DjiCloudCredentialStore.configured(context)){"Enter the DJI Cloud API credentials first."}}}
            .onSuccess{appKey="";license="";onSaved();onDismiss()}.onFailure{error=it.message?:"Could not save protected settings."};busy=false
    }}){Text(if(busy)"Saving…" else "Save setup")}},dismissButton={TextButton(enabled=!busy&&!loading,onClick=onDismiss){Text("Cancel")}})
}

@Composable fun DjiCloudConnectionDialog(session:DjiCloudSession,onBack:()->Unit) {
    val context=LocalContext.current
    var processingError by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(session.missionId){val window=(context as? Activity)?.window;val wasOn=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);onDispose{if(!wasOn)window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    AlertDialog(onDismissRequest=onBack,title={Text("Pilot 2 · ${session.title}")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(session.active){
                Text("In Pilot 2, open Cloud Services → Open Platforms. Enter this URL, then the access code. Keep both devices on the same Wi-Fi.")
                if(session.httpDiagnostic)Text("HTTP diagnostic · controlled LAN only. Credentials and original photos travel without transport encryption.")
                SelectionContainer{Text(session.url,style=MaterialTheme.typography.titleMedium)}
                SelectionContainer{Text("Access code: ${session.accessCode}",style=MaterialTheme.typography.headlineSmall)}
                if(!session.httpDiagnostic){
                    if(!session.importedCertificate)Text("Temporary HTTPS certificate: this is a qualification build. Pilot 2's native clients may need a trusted certificate before they can connect.")
                    Text("Certificate SHA-256",style=MaterialTheme.typography.bodySmall)
                    SelectionContainer{Text(session.fingerprint,style=MaterialTheme.typography.bodySmall)}
                }
            }
            Text("Flight height: ${Units.current.length(session.heightMeters)} above takeoff",style=MaterialTheme.typography.titleMedium)
            Text("Return-home height: ${Units.current.length(session.returnHomeHeightMeters)} above takeoff · target ground detail: ${Units.current.resolution(session.groundResolutionCmPerPixel)}")
            Text("The route uses a fixed height relative to takeoff and assumes level ground. Confirm waypoint heights and terrain clearance in Pilot 2 before starting.")
            if(session.active)Text("Mapping is active: automatic app shutdown is paused and the screen stays awake. Back to mission keeps the connection running; Close connection ends it.")
            if(session.captureDescription.isNotBlank())Text("Capture: ${session.captureDescription}")
            Text("Estimated ${session.estimatedPhotos} photos · ${(session.estimatedSeconds+59)/60} minutes on the route, excluding launch transit, takeoff, landing and reserve. Review terrain, route and clearance outside the assignment in Pilot 2.")
            Text(session.status)
            listOf("androidPageReached" to "Android connection page", "licenseVerified" to "DJI license", "mqttConnected" to "Controller connection", "wsConnected" to "Mission connection",
                "missionLoaded" to "Route library", "automaticOriginals" to "Automatic original photos").forEach{(key,label)->Text("$label: ${if(session.flags[key]==true)"Ready" else "Waiting"}")}
            Text("${session.photos} mission originals verified · ${"%.1f".format(session.bytes/(1024.0*1024))} MiB")
            Text("${session.storedUploadCount} returned files stored on this tablet. Completion reports identify which originals belong to this mission and camera.")
            if(session.photos>=3)TextButton(enabled=!preparing,onClick={
                preparing=true
                scope.launch {
                    runCatching{withContext(Dispatchers.IO){MappingProcessingManager.start(context,session.missionId,expectedPhotos=session.photos,engine="rtk-surface")}}
                        .onSuccess{onBack()}.onFailure{processingError=it.message}
                    preparing=false
                }
            }){Text("Reconstruct ${session.photos} originals · 3D surface + 2D map")}
            processingError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(session.callbackFailures>0)Text("${session.callbackFailures} completion reports could not be accepted. Returned files are retained; Pilot 2 can retry the uploads.")
            if(session.unmatched>0)Text("${session.unmatched} other returned files retained separately; their original flag, mission tag or camera did not match this mission.")
            Text("Pilot 2 reviews and starts the flight. R2C hosts the route and retains the returned original bytes on this tablet.")
        }
    },confirmButton={TextButton(onClick={DjiCloudManager.stop();onBack()}){Text("Close connection")}},dismissButton={TextButton(onClick=onBack){Text("Back to mission")}})
}
