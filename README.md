# SmartShare

An Android accessibility-overlay prototype for the system share chooser.

## Current milestone: 0.1.0

This build is deliberately a diagnostic prototype for the Fanvace M36 Plus / Android 13 test environment.

- Watches for the system activity class `com.android.internal.app.ChooserActivity`.
- Requests access to interactive windows and reads the chooser's accessibility tree only after that chooser is detected.
- Draws a full-screen `TYPE_ACCESSIBILITY_OVERLAY` above the original chooser.
- Lists text-bearing clickable items discovered in the original chooser.
- Attempts to activate the corresponding original chooser item through `AccessibilityNodeInfo.ACTION_CLICK`.
- Stores the last chooser tree and candidate labels in private app preferences only. The app has no INTERNET permission.
- Includes a restricted global list and a hidden full-list gesture: long-press the SmartShare title four times within ten seconds.

## Install and test

1. Download the `SmartShare-debug-apk` artifact from the latest successful run in the repository's **Actions** tab.
2. Install the APK on the Android device.
3. Open SmartShare and enable its accessibility service under Android Accessibility settings.
4. Open any app, invoke Share, and confirm whether SmartShare covers the chooser.
5. Return to SmartShare and tap **Refresh diagnostics** to inspect the detected labels and hierarchy.
6. Mark the labels you want to keep and enable the restricted-list switch.

## Important limitations in this prototype

- Accessibility does not expose the originating share Intent's MIME type to this service. Therefore version 0.1 has a **global** allow-list, not reliable per-file-type rules yet. We need to validate the chooser hierarchy on the target ROM and determine a safe MIME source before implementing type-specific policies.
- Chooser layouts differ between Android builds and OEMs. If the hierarchy does not expose target labels as clickable descendants, the overlay will appear but target activation may fail. The diagnostics screen is intended to reveal that exact case.
- This is an overlay, not a replacement of Android's system component. It does not disable or modify `ChooserActivity`, alter system partitions, or require root.
- Accessibility access is powerful. The service only processes events when the chooser activity is detected, and all diagnostic data stays in private app storage.

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main` and on manual dispatch. Uploaded APK artifacts are retained for 14 days.
