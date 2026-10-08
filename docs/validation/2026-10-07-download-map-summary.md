# Download Map summary — October 7, 2026

Both platforms show width × height (miles), area (square miles), and centroid (decimal latitude, longitude) in Estimated download. Measurements describe the selected download bounding rectangle, explicitly stated in the pane; they are not polygon area measurements. Width follows the center latitude, height follows a meridian, and area uses a spherical rectangle calculation.

Android replaces its compressed estimate text with labeled rows for map/DEM tile counts, download sizes, and conservative total. Capacity is a separate section with current usage, projected usage, configured limit, and available storage. Existing cache controls, warnings, frozen viewport selection, download planning, and retry behavior are preserved. Rows wrap at narrow widths or larger text sizes.

Validation:
- Android debug build passed; two measurement tests passed.
- Apple core: two matching measurement tests passed.
- Signed iOS device build passed; existing unrelated compiler warnings remain.
- Installed in place and launched successfully on A5 Pro (R52Y90C9XST) and Ken’s iPad (694108CB-8CBE-593D-ABE1-D9EDD947B901) at approximately 06:18 PDT. Android reports 2.5.0 (339). The installed iPad artifact is 2.5.0 (339); CoreDevice app inventory still returns an empty list, so installed-version query is unavailable. Physical layout verification remains outstanding. Check both viewport and map-boundary scopes, portrait/landscape, larger text, and changes to DEM/AOL selections on both platforms.
