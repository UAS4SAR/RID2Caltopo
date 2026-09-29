# Narrow Android Main Screen header

Evidence: Screenshot_20260926_172411_RID2Caltopo.jpg supplied by the user, reported Samsung SM-S931U. The title and Teams label wrap to one or two characters per line while Proximity and Bridge retain their width. The user reports that reducing font size or rotating to landscape avoids the problem. Exact device font/display settings and installed build were not provided.

Source cause: MainScreen placed the title/Teams selector and all status controls in CenterAlignedTopAppBar. The actions consumed the available horizontal space before title measurement. Proximity added pressure to this shared row.

Change: Keep the title/Teams selector and overflow menu in the top row. Place proximity, bridge, and conditional alert controls in a separate FlowRow that wraps using measured control widths, including scaled text. Preserve horizontal safe-area insets. Bound title and Teams text to one line each with ellipsis; the Teams dropdown still supplies full credential labels. Preserve proximity status wording, settings/resume actions, and alert behavior.

Apple source audit: apple/App/ContentView.swift presents proximity status in a separate overlay HStack, outside the navigation title. The specific Android title/action competition is absent there. No Apple code was changed or built; this is source inspection, not physical narrow-screen validation.

Physical validation remains pending: no Android device was connected. On the SM-S931U, check portrait and landscape with normal and largest font/display settings, long Teams labels, proximity Off/On/Suspended/Unavailable, and simultaneous compliance/signal-loss controls. Confirm title height stays bounded, status controls remain readable and tappable, Teams/menu actions work, and page content remains reachable. No device installation or store release was performed.

Automated validation: Android debug assembly passed; 25 existing proximity consent/threshold tests passed (zero failures/errors/skips). git diff --check passed. Build/test log: /private/tmp/r2c-narrow-header-validation.log. These tests protect proximity behavior; they do not establish rendered layout correctness. Other horizontally scrollable Main Screen content was not redesigned by this header correction.
