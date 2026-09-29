# Bridge chip navigation and session warning control — 2026-09-29

Implemented in the main project worktree on Android and Apple.

- Main Screen Bridge chip opens Live View. Normal and full-screen Live View Bridge chips return to Main Screen using the existing navigation/cleanup path.
- Removed title-bar page-swipe attachments on both platforms and Android's old title-bar double-tap shortcut. Map/video gestures remain unchanged.
- Added 16 dp/points of separation from the Main Screen menu, and navigation accessibility descriptions/hints.
- Settings > Bridge warnings contains “Bridge audio warnings.” The control applies immediately to in-memory session state, defaults enabled on process/app startup, and is not persisted. Background/foreground transitions do not re-enable a warning deliberately muted in the same session. No Bridge chip mute action or Main Screen menu mute item remains.

Android build and eight bridge monitor tests passed. Physical navigation, narrow-layout, restart-to-enabled, and audible warning tests remain pending. No device installation performed.

Apple signed Debug build and deep/strict signature verification passed; three focused bridge-warning tests passed. Android explicitly clears mute on a fresh activity task (savedInstanceState == null), covering Quit/relaunch with a retained process; configuration recreation preserves the session choice.

Device installation follow-up, 2026-09-29 11:06 PDT: A5 Pro R52Y90C9XST and Ken’s iPad 694108CB-8CBE-593D-ABE1-D9EDD947B901 updated in place and launched successfully. Both report 2.3.7 (304); these rebuilt artifacts retain the existing build number. Android installed/update signing certificates matched; iPad deep/strict signature verification passed. No uninstall or data reset. Physical tests remain with the user.
