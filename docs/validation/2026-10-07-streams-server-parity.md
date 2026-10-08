# Streams Server and AOL explanations — October 7, 2026

- iOS forwards the registered drone-designator action from the stream tile into Streams Server. The server sheet dismisses before the existing registered-aircraft sheet opens, preserving its Add aircraft path.
- Removed the separate iOS Performance menu destination. Stream, decoder, anomaly counters, and device load now appear inside Streams Server.
- Estimated anomaly headroom is the first device-load item on both platforms. Android retains its existing estimator. Apple adds an advisory estimate using the Android CPU thresholds (65% limit, 85% hot) and stream-count thresholds, with iOS fair/serious/critical thermal pressure and unknown until a live stream and CPU delta are available. Apple serious/critical thermal state takes precedence even before a CPU sample.
- Apple samples process CPU and peak resident memory only while this panel is present. Embedded MediaMTX shares the app process, so the UI explicitly labels the aggregate. Apple does not currently measure Android's separate main-thread CPU signal; this is an estimate, not a guarantee of smooth anomaly processing.
- Both platforms now prioritize the approximately 4 km AOL size limit for oversized finite regions, then explain the 70°S–70°N restriction for small polar selections. Invalid-coordinate errors now suggest selecting a different region. This addresses the early generic latitude rejection found in source; the user's exact iPad bounds were not captured.

Validation:
- Android: four SurfacePreparation tests passed; debug APK build passed.
- Apple: four focused surface-preparation/headroom tests passed; signed iOS device build passed.
- git diff --check passed.
- Not installed in this turn. Physical checks remain: droneDesig navigation and Add aircraft, no separate Performance item, combined server content, oversized AOL selection, and advisory headroom under actual live-stream/anomaly load.
