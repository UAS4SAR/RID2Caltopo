# Standalone iPad confirmation suppression

Fresh iPad build-222 evidence: `outputs/ipad-confirmation-222-20260912/ipad.log`. At 18:46:25 the tablet connected to Tracker with empty mapId and received Matrice ownership for another tablet. The stream started at 18:48:54; no confirmation queue or save was logged. The flight later cleared current-flight decisions. Source inspection found peer confirmations were accepted as current-flight decisions even with no incident map selected. This is distinct from the prior Published old-snapshot reset defect.

Standalone devices now require local confirmation. Apple tracks the incident-map scope in its confirmation store, ignores incoming peer confirmations with an empty scope, and clears old peer identities when scope changes. Android filters incoming peer drone_confirmed events on mapless coordination sessions before they can become session decisions. Map-connected peer confirmation remains supported; local Save and identity mappings are preserved. Apple now logs the actual confirmation sheet appearance as well as queueing.

Both unit suites passed. Tests cover mapless/whitespace scope, retaining map-connected peer behavior, and Android's real incoming peer event path. Release metadata is synchronized. Build 223 is the deployment candidate. Physical confirmation presentation remains to be verified after install.

The same device log independently shows automatic AOL calculation becoming numeric without manual calibration. Repeated tiny reference-coordinate changes also cause short numeric/pending refresh transitions; this report changes confirmation scope only and does not claim that separate display behavior is resolved.
