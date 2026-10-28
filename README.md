# RID2Caltopo (Remote ID to Caltopo):  Live Drone Tracking for SAR

This application monitors supported Bluetooth and WiFi interfaces for [ASTM F3411](https://store.astm.org/f3411-22a.html) -
compatible Remote ID location updates and records the updates as a sequence of track waypoints 
that are compatible with [Caltopo](https://www.caltopo.com)'s geo-json file format.   

Additionally, if you have configured caltopo teams credentials properly and your mapid points 
to an existing map that the credentials have write/update permissions for, the app can plot near
real-time* LiveTrack updates into the map.

All drones in service of a SAR organization should be required to emit Remote ID signaling, 
enabling this protocol-based Remote ID receiver to close the previously open loop between
search assignments and actual drone coverage.  Together with Caltopo's "Aircraft" layer, the
~real-time updates from this app allow an air-boss or supplemental Visual Observers to keep 
track of all airborne assets.

The [CalTopo](https://www.caltopo.com) platform is a search-management platform used by many SAR agencies.
RID2Caltopo is an independent project and is not affiliated with or endorsed by CalTopo. It uses
the CalTopo Teams API. The RID2Caltopo developer thanks the CalTopo team for its excellent product
and support of the Teams API. We are also thankful
for the network sniffing and decoding code developed by the [OpenDroneId](https://github.com/opendroneid/receiver-android) project.

Android phones and tablets have limited sensitivity to the Remote ID signalling.  They should
be able to work fine when the drone is within a few dozen feet of the device.  To make 
this tool work for SAR applications, where drone search segments can span many thousands of 
feet, it is recommended that you pair the app with a [Dronescout Bridge](https://www.gearfocus.com/products/new-bluemark-ds100-dronescout-retail-bridge-faa-remote-id-re-rEBYx). Just power-up 
the bridge and raise it up a fair bit to optimize coverage:

<img alt="Dronescout Bridge on antenna mast" src="images/DronescoutBridge.jpg" width="257" height="360">

New for 2026: BlueMark Innovations has released a [tri-band bridge](https://dronescout.co/product/dronescout-bridge-triple-band-ds110-retail-remoteid-receiver-for-ios-android-and-drone/?v=0b3b97fa6688)
that enables capturing 5Ghz WiFi and WiNan signalling commonly used with Skydio drones.  My unit arrived in California in less than 
a week from the Netherlands.
  
Please note: The user of this application must always visually verify that the received Remote ID
signal corresponds to an actual drone seen flying in the air, at the position the signal claims it 
to be.

Use Settings for local preferences and saved account credentials. Organization
access is configured through authenticated tracker enrollment. Local Remote ID
mapping files and map tile packages remain supported; credential-bearing
configuration files and legacy organization/FAA/access-profile QR transfers are
no longer accepted.

## ridmap.json:
Use the ridmap configuration file format to map remoteIDs to more friendly track labels:

<blockquote><code>
{
    "type" : "ct_ridmap",
    "file_version" : "1.0",
    "editor" : "admin@kjt.us",
    "updated" : "Wed Sep 17 12:42:41 PDT 2025",
    "map" : [
        {
            "remoteId" : "1581F6Z9C24BH0036EJL",
            "mappedId" : "1SAR7min4p",
            "org" : "mySAR",
            "owner" : "mySAR",
            "model" : "Mini 4 Pro"
	    },
        {
            "remoteId" : "1581F67QE239L00A00DE",
            "mappedId" : "1SAR7mvc3p",
            "org" : "mySAR",
            "owner" : "mySAR",
            "model" : "Mavic 3 Pro"
        },
    ]
}
</code></blockquote>

The remoteId is the actual identifier broadcast by the drone.   The default value of the mappedId 
is the same as the remoteId, but can be changed in the .json file or the app's user interface.  
Try to pick values for mappedId that identify the Remote Pilot In Command (RPIC), the type of drone, 
and the payload capabilities of the drone.  The other fields are optional and may be omitted or 
left blank in the current version of the app.

### Mapped ID Rules Checking introduced in 1.0.7rc1:
Changes to the mapped ID must include the pilot's Callsign of the form [0-9]?[A-Z]+[0-9]+ 
(for regex junkies) or more simply put:
  1SAR7
  SAR7
  SAR007
  1P16

You can also tack on a brief mnemonic for the drone type and payload. If omitted, then
this app will attempt to convert the model to mnemonic form by removing spaces and vowels.

## CalTopo access and tracker enrollment

CalTopo publishing uses an existing map for which the configured account has
write/update permission. The user selects that map in RID2Caltopo; creating a new
personal CalTopo map is not a prerequisite for the Teams API workflow.

For managed access, a tracker administrator configures the appropriate CalTopo
Teams access on the tracker. Scan the tracker enrollment link/QR and authenticate
as an authorized user. The tracker provisions the device and loads its permitted
configuration. Select an existing map from the team-map picker afterward.
Enrollment and sign-in can complete before a map is selected.

For independently configured Teams access, enter the team ID, credential ID and
credential secret in Settings -> Credentials. A CalTopo team administrator
creates the service account described in the
[Supported API for Teams](https://training.caltopo.com/all_users/team-accounts/teamapi).
Those credentials remain saved locally across launches and updates. App-created
settings backups omit them and do not erase credentials already on the device.

Personal CalTopo browser sign-in is a separate option for the user's own account
and maps. It is not required to select a map authorized through Teams access.

The optional Connect Key selects the CalTopo Shared Locations namespace for
position reports. A team administrator obtains it from the team's tracking
configuration. Enter only the Connect Key, without `FLEET:`, a device ID or the
surrounding URL. Reports identify the aircraft as
`FLEET:<Connect Key>-<Remote ID>` and use CalTopo's position-report endpoint.
Enable the Shared Locations layer to display those reports.

Map tile packages transfer map data without credentials. Credential export/import
files, organization credential QR transfers and the old access-profile sharing
workflow have been removed. Use tracker-hosted enrollment and authenticated access
for another tablet instead of copying account secrets.

## Support for multiple apps writing to same map at the same time:
Each DroneScout Bridge has a limited detection range.  Many factors contribute to the maximum
detection range, including location and height of the bridge, terrain, foliage, and weather 
conditions.   Our first major test of an earlier experimental release of RID2Caltopo and the 
bridge revealed that line of sight is probably the best determinate for coverage, so we added 
support for multiple DroneScout Bridge + RID2Caltopo pairings, which we'll call "R2C Zones" or 
more simply "R2C" instances going forward.

Recent field testing with a DroneScout Bridge and a small RID module showed that the bridge can
still be detected at a few hundred feet in favorable conditions, but reception is not continuous
enough at that distance to treat it as an operator-trustworthy link.  As a conservative operating
guideline, keep the tablet or phone running RID2Caltopo within about 10 meters of its paired
DroneScout Bridge.  If multiple DroneScout Bridges are deployed for the same incident, separate
the bridges by at least 200 meters when practical.  Placing several bridges close together can make
local Bluetooth congestion worse by causing multiple strong bridge transmitters to rebroadcast
near the same tablet instead of improving coverage.

Each R2C instance needs to have network connectivity to write to the map and to connect with it's 
peers.  Networks can be cellular data or wireless.  In the Sierras, we may end up setting up 
battery powered Starlink Minis if we can't locate our R2C instance high enough to get cell
coverage.

When RID2Caltopo connects to a map, it creates a marker at the device's current location in the 
"track_folder" specified above.

If `tracker_api_key` and `tracker_url_prefix` are configured, RID2Caltopo will prefer
tracker-backed peer coordination. Each R2C zone opens an outbound connection to the
organization's `r2c-tracker` service, reports its presence and approximate location, and
submits first-sighting and waypoint candidate updates for each observed Remote ID. The
tracker service acts as the rendezvous point and ownership arbiter: it assigns one zone as
the owner for each drone stream and relays candidate sightings to that owner. The owner is
then responsible for suppressing duplicates, enforcing strictly increasing waypoint
timestamps, and writing the accepted track into the CalTopo map.

If tracker coordination is not configured, RID2Caltopo falls back to MQTT-based peer
coordination. That path uses the map-scoped MQTT topic space to negotiate ownership and
share peer state. If neither tracker nor MQTT coordination is available, or if peer
coordination is explicitly disabled in the app's testing tools, each R2C zone writes its
own track independently and duplicate tracks may appear in CalTopo.

## R2C Site selection
Your team's Remote Pilot In Command 
([RPIC](https://www.ecfr.gov/current/title-14/chapter-I/subchapter-F/part-91#91.3)) 
and search planning coordinators should ideally work together to identify the characteristics of 
reasonable boundaries for drone search segments as well as potential sites for each R2C instance.
It's best if you can do this first in training sessions, where you have the gift of time to 
discuss the tradeoffs involved.   The best search segment boundaries will generally all lie within 
the range of the Visual Observers (VOs) at minimum.   If you have the necessary BVLOS (Beyond Visual 
Line Of Sight) waiver, the other consideration is the effective range of your controllers telemetry 
while staying within the required maximum AGL altitude.   Fortunately, factors affecting controller 
telemetry are the same that affect the detection range of the DroneScout bridge (both operate on the 
same frequencies).   

Try to establish the site location for the Bridge and R2C as high as possible.  Amazon has [tall 
tripods](https://www.amazon.com/s?k=tall+tripod) that you can use to elevate the bridge and a 
corresponding power bank 15' or more above the ground.  If your tripod is tall enough, you might
consider placing the cell phone running RID2Caltopo on the same tripod.  It isn't necessary to
place the device running RID2Caltopo right next to the bridge, but keep it within about 10 meters of the
bridge when possible.  By placing the entire setup at higher locations, you can improve your chance
of getting cell coverage.

## How to build
To build the application, use Android Studio.
Import the project (File -> New -> Import Project, or just Open on newer versions of Android Studio) 
and point to the root folder. Then Build -> Make Project.

For production versioning, signed Android artifacts, Apple/TestFlight builds,
physical qualification, store staging, and recovery procedures, follow the
[mobile release runbook](RELEASE.md).

## Detector documentation

RID2Caltopo includes an Anomaly Detector that can help searchers identify
regions of interest in infrared and visible-light drone video. For a
non-technical introduction to its infrared, color, target-color, motion, and
saliency capabilities, see [Anomaly Detector: An Introduction for SAR Users](docs/Anomaly_Detector_Introduction_for_SAR.md).

For technical infrared detector design notes, see
[IR Anomaly Detector](docs/IR_Anomaly_Detector.md).

For the current visible-light detector architecture, including Color Outlier,
Target Colors, realtime scheduling, and qualification, see
[Color Anomaly Detector](docs/Color_Anomaly_Detector.md).

## MediaMTX
The latest version of this app bundles a version of the MediaMTX 1.16.2 server that has been tailored
to the sensitivities of various DJI and Autel drone controllers and to support low-latency playback.
RID2Caltopo stores only the source patches and reproducible build instructions under
[`third_party/mediamtx`](third_party/mediamtx); generated executables are intentionally excluded from Git.
MediaMTX remains licensed under its MIT license; see
[Third-Party Software Notices](THIRD_PARTY_NOTICES.md).


## Supported interfaces and protocols
Bluetooth 4 (legacy bluetooth), Bluetooth 5 (long range/coded phy), WiFi Beacon, and WiFi NaN are all 
supported by the app. While bluetooth 4 seems to be universally supported, some phones/tablets may not 
support one or more of the other capabilities.

## Privacy Policy
The app handles operational data, media, settings, and diagnostics as described
in the privacy policy for your device:

- [iPhone and iPad privacy policy](PrivacyPolicyApple.md)
- [Android privacy policy](PrivacyPolicyAndroid.md)

## License

RID2Caltopo's original source code and documentation are licensed under the
[Apache License 2.0](LICENSE). Third-party components retain their respective
licenses; see [Third-Party Software Notices](THIRD_PARTY_NOTICES.md) and
[NOTICE](NOTICE).

RID2Caltopo is an operational aid rather than a certified aviation, navigation,
or life-safety system. Review the [operational disclaimer](DISCLAIMER.md) before
using it in the field. Contributions are welcome under the terms described in
[CONTRIBUTING.md](CONTRIBUTING.md).
