package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.ui.AlertDialog

private data class MappingMissionPreview(val key: Any, val route: Result<DjiMappingRoute?>, val storage: MappingStorageEstimate?)

@Composable
private fun MappingChoiceField(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(value=value, onValueChange={}, readOnly=true, label={Text(label)}, enabled=enabled,
            trailingIcon={ IconButton(enabled=enabled,onClick={expanded=true}) { Icon(Icons.Filled.ArrowDropDown,contentDescription="Choose $label") } },
            modifier=Modifier.fillMaxWidth().clickable(enabled=enabled) { expanded=true })
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { (id,title) -> DropdownMenuItem(text={Text(title)},onClick={expanded=false;onSelected(id)}) }
        }
    }
}

@Composable
fun MappingMissionDialog(mission: MappingMission, onLayersChanged: () -> Unit, onDismiss: () -> Unit, onViewLayer: (MappingLayer) -> Unit = {}) {
    val context = LocalContext.current
    var selectedAircraft by rememberSaveable { mutableStateOf(MappingResolution.MATRICE_4TD) }
    var selectedProfileId by rememberSaveable { mutableStateOf("m4td.visible.wide") }
    var aircraftPreferenceReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        selectedAircraft=MappingAircraftPreferences.load(context)
        val profiles=MappingResolution.profilesFor(selectedAircraft)
        if(profiles.none{it.id==selectedProfileId})selectedProfileId=profiles.firstOrNull()?.id ?: ""
        aircraftPreferenceReady=true
    }
    var selectedCaptureId by rememberSaveable { mutableStateOf(MappingCaptureProfile.defaultVisible.id) }
    var targetGsdText by rememberSaveable { mutableStateOf(Units.current.number(Units.current.resolutionValue(2.0), 4)) }
    var clearanceText by rememberSaveable { mutableStateOf(Units.current.number(Units.current.lengthValue(15.0),2)) }
    var clearanceMeters by rememberSaveable { mutableStateOf(15.0) }
    var captureSpeedText by rememberSaveable { mutableStateOf(Units.current.number(Units.current.lengthValue(4.0),2)) }
    var transitSpeedText by rememberSaveable { mutableStateOf(Units.current.number(Units.current.lengthValue(4.0),2)) }
    var transitSpeedMeters by rememberSaveable { mutableStateOf(4.0) }
    var captureSpeedMeters by rememberSaveable { mutableStateOf(4.0) }
    var obstacleText by rememberSaveable { mutableStateOf("") }
    val obstacleMeters=if(obstacleText.isBlank())null else Units.current.lengthMeters(obstacleText.toDoubleOrNull() ?: Double.NaN)
    val canopyHeight=obstacleMeters
    var card by remember { mutableStateOf<java.io.File?>(null) }
    var storageReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        card=withContext(Dispatchers.IO){runCatching{MappingVolume.card(context)}.getOrNull()}
        storageReady=true
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showCloudSetup by rememberSaveable { mutableStateOf(false) }
    var cloudConfigured by remember { mutableStateOf(false) }
    LaunchedEffect(showCloudSetup) {
        if(!showCloudSetup)cloudConfigured=withContext(Dispatchers.IO){DjiCloudCredentialStore.configured(context)}
    }
    var showCloud by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val cloudSession by DjiCloudManager.session.collectAsState()
    val capacity by produceState<Pair<Long,Long>?>(null,card,storageReady) {
        value=null
        if(!storageReady)return@produceState
        while(isActive){value=withContext(Dispatchers.IO){val volume=card ?: context.filesDir;volume.totalSpace to volume.usableSpace};delay(5000)}
    }
    val freeBytes=capacity?.second
    val scope = rememberCoroutineScope()
    val targetGsd = targetGsdText.toDoubleOrNull()?.let(Units.current::resolutionCm)
    val cameraProfiles = MappingResolution.profilesFor(selectedAircraft)
    val activeProfile = cameraProfiles.firstOrNull { it.id == selectedProfileId } ?: cameraProfiles.firstOrNull()
    val profileReady = activeProfile != null
    val estimatedHeight = activeProfile?.let { profile -> targetGsd?.let { MappingResolution.heightMetersForGsd(it,profile.sensor) } }
    val capture=if(activeProfile?.isVisibleWide==true)MappingCaptureProfile.profiles.first{it.id==selectedCaptureId} else MappingCaptureProfile.nadir
    val previewKey=remember(mission,activeProfile,targetGsd,capture,canopyHeight,obstacleMeters,clearanceMeters,captureSpeedMeters,transitSpeedMeters){Any()}
    // Serial execution bounds overlapping calculations while edits cancel stale requests.
    val planningDispatcher=remember {Dispatchers.Default.limitedParallelism(1)}
    val prepared by produceState<MappingMissionPreview?>(null,previewKey) {
        delay(150)
        val preview=withContext(planningDispatcher) {
            val result=runCatching {
                require(capture!=MappingCaptureProfile.brushTrees||obstacleMeters!=null){"Enter the maximum obstacle top above takeoff for brush/tree photo spacing and clearance."}
                activeProfile?.let{DjiMappingRoute(mission.json(it,targetGsd,capture,canopyHeight ?: Double.NaN,obstacleMeters,clearanceMeters,captureSpeedMeters,transitSpeedMeters))}
            }
            MappingMissionPreview(previewKey,result,result.getOrNull()?.let{runCatching{MappingStorageEstimate.plan(it.mission,it.photoEstimate)}.getOrNull()})
        }
        value=preview
    }
    val currentPreview=prepared?.takeIf{it.key===previewKey}
    val routePreview=currentPreview?.route ?: Result.success(null)
    val route=routePreview.getOrNull()
    val storageEstimate=currentPreview?.storage
    val clearanceProblem = if(canopyHeight!=null && estimatedHeight!=null && canopyHeight>estimatedHeight-clearanceMeters) {
        val minimumGsd=activeProfile?.let { MappingResolution.gsdCmPerPixel(canopyHeight+clearanceMeters,it.sensor) }
        "Flight height ${Units.current.length(estimatedHeight)} must be at least ${Units.current.length(canopyHeight+clearanceMeters)} for ${Units.current.length(canopyHeight)} maximum obstacle top plus ${Units.current.length(clearanceMeters)} clearance." +
            (minimumGsd?.let { " This camera needs a coarser ground resolution of at least ${Units.current.resolution(it)}, or select a longer lens to preserve the requested detail." } ?: "")
    } else null
    val canTransfer = aircraftPreferenceReady && storageReady && capture.exportQualified && route!=null && (route.photoEstimate<=200||card!=null) && route.photoEstimate<=MappingVolume.MAX_PHOTOS && !busy && activeProfile != null && targetGsd != null && targetGsd in 0.2..100.0 && estimatedHeight != null && estimatedHeight in 5.0..500.0
    if(showCloudSetup){DjiCloudSetupDialog({showCloudSetup=false},{cloudConfigured=true});return}
    if(showCloud && cloudSession!=null){DjiCloudConnectionDialog(cloudSession!!){showCloud=false};return}
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Define Map Mission") }, text = {
        Column(modifier=Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value=mission.mapName.ifBlank { "Connected incident map" },onValueChange={},readOnly=true,
                label={Text("Map / incident")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=mission.title,onValueChange={},readOnly=true,
                label={Text("Search assignment")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("Capture settings",style=MaterialTheme.typography.titleSmall)
            MappingChoiceField("Aircraft", selectedAircraft, MappingResolution.aircraftModels.map { model ->
                model to MappingResolution.aircraftLabel(model)
            }, !busy && aircraftPreferenceReady) { selectedAircraft=it;MappingAircraftPreferences.select(it);MappingResolution.profilesFor(it).firstOrNull()?.let { profile ->
                selectedProfileId=profile.id
            } }
            if (cameraProfiles.isNotEmpty() && activeProfile != null) {
                MappingChoiceField("Camera / sensor", activeProfile.label, cameraProfiles.map { it.id to it.label }, !busy) { id ->
                    selectedProfileId=id

                }
            } else {
                Text("Camera and sensor profiles for $selectedAircraft have not been verified in R2C yet. Mission height/resolution estimates are disabled for this aircraft.")
            }
            OutlinedTextField(targetGsdText, { targetGsdText=it }, label={Text("Target ground resolution (${Units.current.resolutionUnit})")}, singleLine=true,
                enabled=!busy && profileReady, keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal), modifier=Modifier.fillMaxWidth().testTag("mapping-target-gsd"))
            if (estimatedHeight != null && profileReady) {
                Text("Estimated height above takeoff: ${Units.current.length(estimatedHeight)} · about ${Units.current.number((if (Units.current == DisplayUnits.METRIC) 100.0 else 30.48) / targetGsd!!)} pixels/${Units.current.lengthUnit}")
                if(showDetails)Text("Height uses level-ground nadir sampling and ${activeProfile!!.sensor.widthPixels} × ${activeProfile.sensor.heightPixels} originals. Pixel sampling is not surveyed accuracy.")
            } else if (profileReady) Text("Enter a target between ${Units.current.resolution(0.2)} and ${Units.current.resolution(100.0)}.")
            if(activeProfile?.isVisibleWide==true) {
                MappingChoiceField("Capture pattern",capture.label,MappingCaptureProfile.profiles.map{it.id to it.label},!busy){selectedCaptureId=it}
                Text(capture.description)
            }
            if(activeProfile?.focalLength35mm!=null)Text("Telephoto survey · nadir −90°.")
            if(activeProfile != null && !activeProfile.thermal) {
                Text("Automatic setup: selected lens, VIS-only capture, and original-photo return when connected to Pilot 2.")
                Text(activeProfile.captureSettings + " R2C cannot yet set or verify these camera settings before capture.")
            }
            OutlinedTextField(captureSpeedText,{captureSpeedText=it;captureSpeedMeters=it.toDoubleOrNull()?.let(Units.current::lengthMeters) ?: Double.NaN},
                label={Text("Maximum speed while capturing (${Units.current.lengthUnit}/s)")},singleLine=true,enabled=!busy,
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("mapping-capture-speed"))
            Text("The planner may reduce capture speed to allow time between photos.")
            OutlinedTextField(transitSpeedText,{transitSpeedText=it;transitSpeedMeters=it.toDoubleOrNull()?.let(Units.current::lengthMeters) ?: Double.NaN},
                label={Text("Transit speed (${Units.current.lengthUnit}/s)")},singleLine=true,enabled=!busy,
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("mapping-transit-speed"))
            Text("Used to reach the first waypoint and resume an interrupted route. Return-to-Home speed is controlled by the aircraft/Pilot 2.")
            Text("Obstacle clearance",style=MaterialTheme.typography.titleSmall)
            OutlinedTextField(obstacleText,{obstacleText=it},label={Text("Maximum obstacle top above takeoff (${Units.current.lengthUnit} ATO)")},singleLine=true,enabled=!busy,
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("mapping-obstacle-height"))
            Text("Include terrain rise and antennas, trees or buildings along the full route, including outside the boundary. For brush/tree surveys, this also sets photo spacing and is required. For open ground, blank means not assessed.")
            OutlinedTextField(clearanceText,{clearanceText=it;clearanceMeters=it.toDoubleOrNull()?.let(Units.current::lengthMeters) ?: Double.NaN},label={Text("Clearance margin (${Units.current.lengthUnit})")},singleLine=true,enabled=!busy,
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("mapping-clearance-margin"))
            if(obstacleMeters!=null && obstacleMeters.isFinite() && clearanceMeters.isFinite() && clearanceMeters>0)Text("Minimum flight height: ${Units.current.length(obstacleMeters+clearanceMeters)} above takeoff")
            if(obstacleMeters!=null && obstacleMeters.isFinite() && obstacleMeters>=0 && estimatedHeight!=null)Text("Clearance above entered obstacle: ${Units.current.length(estimatedHeight-obstacleMeters)}. Selected clearance margin: ${Units.current.length(clearanceMeters)}.")
            if(obstacleMeters!=null && obstacleMeters.isFinite() && obstacleMeters>=0 && clearanceMeters.isFinite() && clearanceMeters>0 && activeProfile!=null) {
                MappingResolution.gsdCmPerPixel(obstacleMeters+clearanceMeters,activeProfile.sensor)?.let { minimum ->
                    Text("Finest resolution at minimum flight height: ${Units.current.resolution(minimum)} with this camera.")
                }
            }
            Text("Route estimate",style=MaterialTheme.typography.titleSmall)
            route?.let { preview ->
                Text("Planned capture speed: ${Units.current.length(preview.speed,2)}/s · Transit speed: ${Units.current.length(preview.transitSpeed,2)}/s",modifier=Modifier.testTag("mapping-planned-speed"))
                Text("About ${preview.photoEstimate} originals · ${"%.1f".format(preview.durationEstimateSeconds/60.0)} min on the route · ${"%.1f".format(preview.interval)} s between photos while moving",modifier=Modifier.testTag("mapping-route-estimate"))
                if(showDetails)Text("Photos are triggered by distance while moving along capture legs.")
                if(showDetails)preview.passes.forEach { pass ->
                    val tilt=Math.toRadians(90+pass.pitchDegrees)
                    Text("${pass.pitchDegrees.toInt()}° pitch · ${pass.directionDegrees.toInt()}° grid (alternating headings) · ${pass.forwardOverlapPercent.toInt()}% forward / ${pass.sideOverlapPercent.toInt()}% side overlap · ${"%.2f".format(preview.passSpacing(pass)/preview.speed)} s/photo")
                    if(pass.pitchDegrees> -90)Text("Oblique centre sampling: ${Units.current.resolution(targetGsd!!/kotlin.math.cos(tilt))} × ${Units.current.resolution(targetGsd/kotlin.math.cos(tilt).let{it*it})}. Aircraft offset up to ${Units.current.length(preview.heightMeters*kotlin.math.tan(tilt))} beyond the target centreline; review clearance outside the assignment.")
                }
                if(showDetails)Text("Route time excludes takeoff, landing, transit and battery reserve. Review the complete route and clearance in Pilot 2 before flight.")
                if(preview.photoEstimate>200)Text(if(card==null)"Insert a writable microSD card to prepare this flight." else "This flight will use resumable regional reconstruction on microSD.")
            }
            Text("Storage",style=MaterialTheme.typography.titleSmall)
            storageEstimate?.let { estimate->
                Text("Estimated storage needed: ${MappingStorageUsage.format(estimate.peakAdditionalBytes)} at peak · originals, processing files and finished maps")
                capacity?.let { (total,free)->if(estimate.warn(total,free))Text("Storage warning: this flight may leave the ${if(card!=null)"microSD card" else "tablet"} over 80% full. Free space or reduce the assignment before capture.",color=MaterialTheme.colorScheme.error) }
            }
            freeBytes?.let { Text("${if(card!=null)"Free microSD mapping storage" else "Free tablet storage"}: ${MappingStorageUsage.format(it)}") }
            if(capture in MappingCaptureProfile.approved60Trials)Text("The aircraft can fly outside the assignment to aim the −60° camera inward. Route-end margins add 10 m; review the full path and image coverage in Pilot 2.")
            if(routePreview.isFailure)Text(clearanceProblem ?: routePreview.exceptionOrNull()?.message ?: "Route cannot be estimated.",color=androidx.compose.material3.MaterialTheme.colorScheme.error)
            if(!cloudConfigured)Text("DJI app registration is unavailable. Install a complete app build or check administrator overrides.",color=androidx.compose.material3.MaterialTheme.colorScheme.error)
            if(route!=null&&route.photoEstimate>MappingVolume.MAX_PHOTOS)Text("This route exceeds the 10,000-original intake capacity.")
            TextButton(onClick={showDetails=!showDetails}){Text(if(showDetails)"Hide route details" else "Route details")}
            if(showDetails) {
                mission.launchLocation?.let { launch -> Text("R2C launch reference: ${"%.5f".format(launch.latitude)}, ${"%.5f".format(launch.longitude)} (±${Units.current.length(launch.horizontalAccuracyMeters.toDouble())}). Terrain elevation is not included.") }
                    ?: Text("No fresh R2C location fix was available for the launch reference.")
            }
            cloudSession?.takeIf{it.active}?.let { TextButton(enabled=!busy,onClick={showCloud=true}){Text("Pilot 2 connection status")} }
            TextButton(enabled=!busy,onClick={showCloudSetup=true}){Text("Pilot 2 connection settings")}
            error?.let { Text(it,color=androidx.compose.material3.MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled=canTransfer&&cloudConfigured,modifier=Modifier.testTag("mapping-http-connect"),onClick={busy=true;error=null;scope.launch{
            runCatching{withContext(Dispatchers.IO){DjiCloudManager.start(context,mission,activeProfile!!,targetGsd!!,httpDiagnostic=true,capture=capture,maximumObstacleHeightMeters=obstacleMeters,canopyHeightMeters=canopyHeight ?: Double.NaN,clearanceMeters=clearanceMeters,maximumCaptureSpeedMetersPerSecond=captureSpeedMeters,transitSpeedMetersPerSecond=transitSpeedMeters)}}
                .onSuccess{showCloud=true}.onFailure{error=it.message?:"HTTP LAN connection could not start."};busy=false
        }}){Text(if(busy)"Preparing HTTP…" else "Test Pilot 2 using HTTP")}
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } })
}
