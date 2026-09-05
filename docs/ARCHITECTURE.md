# Kin — Family Health & Emergency Reach-In

A **privately sideloaded** Android app for two people (a person and their mom) to
share health/vitals from their watches and, in an emergency, open a live video/audio
link to each other's phone. Not distributed via Play Store, so it may use permissions
and patterns Play policy would reject. Installed only on the two owners' phones.

## Users & devices
- Two Android phones (owners: paired 1:1).
- Watches: one Pixel Watch (feeds Fitbit → Health Connect), one Galaxy Watch (feeds
  Samsung Health → Health Connect). We read **only** from Health Connect, so both
  brands are covered by one API.

## Product goals
1. Each person sees the other's recent vitals: heart rate, resting HR, steps, sleep, SpO2.
2. Either person can trigger an **emergency reach-in**: a high-priority push wakes the
   other phone and opens a live WebRTC video+audio call that auto-connects.
3. Alerts: notify the partner on watch fall/SOS, or on an abnormal/absent vitals signal.

## Non-negotiable OS realities (design around these, do not try to defeat)
- Camera/mic **privacy indicator** always shows when live (Android 12+). Acceptable —
  the person being viewed should know.
- **Background camera** is blocked (Android 11+). The call MUST run inside a
  **foreground service** with a visible notification before opening the camera.
- Runtime permissions (camera, mic, each Health Connect type) require a one-time grant
  during onboarding. We do NOT pursue Device Owner (would require factory reset).
- Full-screen incoming UI over lock screen uses `USE_FULL_SCREEN_INTENT` +
  `SYSTEM_ALERT_WINDOW` + a full-screen-intent notification.

## Reach-in flow: BOTH paths, configurable (user chose "both/configurable")
- **Primary — Self-answer:** high-priority FCM data message wakes the callee app; a
  foreground service posts a full-screen-intent notification that launches the in-app
  call screen directly and auto-answers. No dependence on the system dialer.
- **Fallback — Accessibility auto-answer:** an optional `AccessibilityService` that, if
  enabled, taps the "Answer" action on an incoming call UI. Off by default; user opts in.
- A settings toggle `reachInMode = SELF_ANSWER | ACCESSIBILITY` selects behavior.

## Tech stack (pin these versions; all coding agents must match)
- Kotlin 2.0.x, AGP 8.5.x, Gradle 8.9, JDK 17.
- minSdk 29, targetSdk 34, compileSdk 34.
- Jetpack Compose (BOM 2024.09.00), Material3, Navigation-Compose.
- Health Connect: `androidx.health.connect:connect-client:1.1.0-alpha07`.
- Firebase: BoM 33.3.0 — `firebase-messaging-ktx`, `firebase-firestore-ktx`,
  `firebase-auth-ktx`. (google-services plugin; a placeholder `google-services.json`
  goes in `app/` with instructions to replace it.)
- WebRTC: `io.github.webrtc-sdk:android:114.5735.10` (Maven Central, no Play dep).
- Coroutines, `androidx.lifecycle` (viewmodel/runtime-compose), `androidx.datastore`
  (preferences) for settings, `androidx.startup` optional.

## Package layout — each feature owns a package; agents DO NOT edit each other's files
Root package: `com.kin.familyhealth`
```
app/src/main/java/com/kin/familyhealth/
  KinApp.kt                     (Application; Firebase init)               [FOUNDATION]
  MainActivity.kt               (Compose host + NavGraph)                  [FOUNDATION]
  ui/theme/                     (Theme.kt, Color.kt, Type.kt)             [FOUNDATION]
  core/                         (Result types, dispatchers, constants)     [FOUNDATION]
  data/settings/                (SettingsRepository via DataStore)         [FOUNDATION]
  onboarding/                   (permission flow, pairing screen)          [AGENT-ONBOARD]
  vitals/                       (Health Connect read, models, repo, UI)    [AGENT-VITALS]
  sync/                         (Firestore vitals push/subscribe, FCM)     [AGENT-SYNC]
  call/                         (WebRTC service, signaling, call UI,       [AGENT-CALL]
                                 accessibility auto-answer, boot receiver)
  dashboard/                    (partner vitals dashboard screen)          [AGENT-VITALS]
```

## Shared contracts (FOUNDATION defines; feature agents consume — do not redefine)
- `core/Constants.kt`: `APP_PACKAGE`, notification channel ids
  (`CHANNEL_CALL`, `CHANNEL_ALERT`, `CHANNEL_SYNC`), FCM data keys
  (`KEY_TYPE`, `KEY_CALLER_ID`, `KEY_SDP`, `KEY_CANDIDATE`, `KEY_ROOM`), and
  message-type values (`TYPE_EMERGENCY_CALL`, `TYPE_SIGNAL`, `TYPE_ALERT`).
- `data/settings/SettingsRepository.kt`: exposes `Flow`s for `partnerUid`, `myDisplayName`,
  `reachInMode`, `accessibilityOptIn`, and suspend setters. Backed by DataStore.
- `vitals/model/Vitals.kt`: a serializable snapshot data class the sync layer reads.
  Fields: `uid, timestampEpochMs, heartRateBpm?, restingHrBpm?, steps?, sleepMinutes?,
  spo2Percent?, batteryPct?`. Firestore doc = `partners/{uid}/latest`.
- Navigation routes (in `MainActivity` NavGraph): `onboarding`, `dashboard`, `call/{callerId}`,
  `settings`. Feature agents expose a single `@Composable EntryScreen(nav, ...)` per route.

## AndroidManifest — FOUNDATION declares ALL of it up front so feature agents never edit it
Permissions: INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS,
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE,
FOREGROUND_SERVICE_MEDIA_PLAYBACK, USE_FULL_SCREEN_INTENT, SYSTEM_ALERT_WINDOW,
WAKE_LOCK, RECEIVE_BOOT_COMPLETED, MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, and the Health Connect read permissions
(`android.permission.health.READ_HEART_RATE`, `READ_STEPS`, `READ_SLEEP`,
`READ_RESTING_HEART_RATE`, `READ_OXYGEN_SATURATION`).
Components declared (empty stubs are fine at foundation time; agents fill the classes):
- `.MainActivity` (launcher, `showWhenLocked`/`turnScreenOn` NOT here — the call activity handles it).
- `.call.CallActivity` (fullScreen, showWhenLocked, turnScreenOn, singleTask).
- `.call.CallForegroundService` (foregroundServiceType="camera|microphone").
- `.sync.KinMessagingService` (FCM, intent-filter MESSAGING_EVENT).
- `.call.CallAccessibilityService` (BIND_ACCESSIBILITY_SERVICE, meta-data config).
- `.call.BootReceiver` (RECEIVE_BOOT_COMPLETED).
- Health Connect rationale activity intent-filter (`ACTION_SHOW_PERMISSIONS_RATIONALE`).

## Build/verify gate (QA — the commander runs this after integration)
`./gradlew :app:assembleDebug` must succeed offline-friendly. Coding agents should keep
code compiling; if a dependency can't resolve in the sandbox, leave a clearly-marked TODO
and stub the type rather than break the build graph.

## Security & privacy stance
- Pairing is explicit and mutual; only the paired `partnerUid` can trigger a reach-in.
- Reach-in always shows the privacy indicator and a persistent call notification.
- No third-party analytics. Firestore rules restrict each doc to the two paired UIDs
  (rules file provided as `firestore.rules`).
