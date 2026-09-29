# Build 304 device installation — 2026-09-29

Both platforms built from /Users/kjt/Projects/RID2Caltopo as 2.3.7 (304), retaining all current workspace changes. Includes clue-description parity, Live View network identity refresh, and shared thumbnail/LiveTrack interval. Automated tests and builds for these changes are recorded in their individual validation notes. Build-number-only rebuilds succeeded.

Android A5 Pro R52Y90C9XST: installed and new APK signing certificates matched; adb install -r succeeded without uninstall or data reset. Activity launch succeeded and queried package reports versionName 2.3.7 / versionCode 304; running process confirmed.

iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901: signed build verified with deep/strict codesign check; devicectl in-place install and launch succeeded. Queried installed metadata confirms 2.3.7 (304).

Physical regression testing remains with the user; installation evidence does not establish physical qualification.
