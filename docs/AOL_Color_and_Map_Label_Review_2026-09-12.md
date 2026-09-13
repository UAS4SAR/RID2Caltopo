# AOL color and map-label drawing order

The map-label color rule on both platforms searched for `AOL:-`, inadvertently matching the pending placeholder `AOL:--`. The Apple video display used the same test to recolor its entire telemetry line; Android video also recolored the entire line based on the stored clearance.

Both map and video now highlight only a displayed, finite numeric AOL value below zero. Pending (`--`), unavailable (`Unk`), stale (`POS?`), positive, and displayed zero values remain the normal color. Map status attributed text is rebuilt with an explicit white base on each Apple update so reused annotation views cannot retain a previous highlight. Android detail text also checks freshness and availability before applying red.

Apple aircraft annotations now use maximum normal and selected drawing priority; artifact annotations retain the default unselected priority even when selected. This keeps artifact markers below aircraft labels. Android already appends its drone-label overlay after artifact overlays, so its drawing order is unchanged.

Regression cases cover pending, unavailable, stale, positive, zero, negative zero, and negative numeric values, with only the AOL token selected for highlighting. Physical overlap and telemetry-transition testing remains necessary; no installation was performed in this change.

Validation: Apple core suite passed (330 Swift Testing tests plus 10 XCTest tests); Android targeted pilot-display and designator tests passed; final signed iPad build 210 passed. Devices remain on installed build 209.
