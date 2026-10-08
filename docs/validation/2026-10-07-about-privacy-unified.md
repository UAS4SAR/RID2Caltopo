# Combined operational About & Privacy — October 7, 2026

Both the top-bar RID2Caltopo chip and Main Menu > About & Privacy display the combined content. The centered operational header preserves device name, version/build, release date, website, uptime, Tracker status, and TeamDrones count. Support: help@uas4sar.com replaces Developer: kjt@uas4sar.com and opens the mail composer only when tapped. Header contact links retain high-contrast dark/light surfaces. Existing privacy content remains below the header and the main-menu entry remains available.

Android uses one dialog with the shared operational header. Apple uses the same AboutPrivacyView and shared header for the chip's sheet and menu's navigation destination. Other pre-operational privacy entry points retain their existing content without requiring operational state.

Validation: Android debug build and signed iOS device build passed. git diff --check passed. Not installed for this change; physical checks remain for both entry points, large-text scrolling, and contact link actions. No external messages sent.
