# Kin — Family Health & Emergency Reach-In

A **private, sideload-only** Android app for two people (e.g. you and your mom)
to share health vitals from your watches and, in an emergency, open a live
video/audio link to each other's phone that **auto-connects** without the other
person needing to tap "answer".

Built for two Android phones with a **Pixel Watch** (Fitbit) and a **Galaxy
Watch** (Samsung Health). Both watches feed **Health Connect**, so the app reads
one API and covers both brands. Not distributed through the Play Store, so it
uses permissions and patterns Play policy would reject — but it still respects
the OS-level rules it cannot (and should not) defeat (see below).

## What it does

- **Shared vitals dashboard** — heart rate, resting HR, steps, sleep, SpO2,
  last-updated time. Each phone reads its own Health Connect data and syncs a
  snapshot through Firestore; each person sees the other's latest.
- **Emergency reach-in** — either person taps "Reach in". A high-priority push
  wakes the other phone and a WebRTC video+audio call auto-connects.
  Configurable, per the two paths you asked for:
  - **Self-answer (default)** — the app opens its own full-screen call screen
    over the lock screen and connects itself. No system dialer involved.
  - **Accessibility auto-answer (opt-in)** — an Accessibility service taps the
    "Answer" action. Off by default; enabled in Settings.
- **Pairing** — the two phones swap one code each, once, during onboarding.

## The OS rules we work *with*, not around

Even sideloaded and self-signed, these are enforced by Android itself:

- The **green camera/mic indicator** always shows when the call is live. Good —
  the person being viewed should know.
- **Background camera is blocked**; the call runs inside a **foreground service**
  with a visible notification, then opens the camera. That's the design.
- **Runtime permissions** (camera, mic, each Health Connect type) are granted
  once, by hand, during onboarding. We deliberately do **not** use Device Owner
  (it would require factory-resetting the phones).

## Architecture

```
app/  (single Android module, package com.kin.familyhealth)
  core/        shared contracts: Constants, SignalingClient, VitalsSync, CallLauncher
  data/settings/  DataStore settings (partner uid, reach-in mode, opt-in)
  vitals/      Health Connect reader + repository + view model
  dashboard/   partner vitals UI + "Reach in" button
  sync/        Firestore vitals + WebRTC signaling, FCM wake service, pairing
  call/        WebRTC session, foreground call service, full-screen call UI,
               accessibility auto-answer, boot receiver
  onboarding/  permission flow, pairing, settings (reach-in mode toggle)
  di/          ServiceLocator — wires the concrete impls to the interfaces
server/        Cloud Function that relays emergency pushes (deploy once)
firestore.rules  security rules (paired-uids-only)
docs/ARCHITECTURE.md  the full design spec
```

The one piece that needs a server: a phone can't send a wake-push to another
phone directly, so `startEmergencyCall()` writes a request to Firestore and a
small **Cloud Function** (`server/functions/index.js`) relays it as a
high-priority FCM message. Everything else runs on-device.

## Setup (once)

1. **Create a Firebase project** at <https://console.firebase.google.com>.
   Enable: **Authentication → Anonymous**, **Firestore**, **Cloud Messaging**.
2. **Register the Android app** with package name `com.kin.familyhealth`,
   download the real **`google-services.json`**, and replace the placeholder at
   `app/google-services.json`.
3. **Deploy rules + function**:
   ```bash
   npm i -g firebase-tools
   cd server && firebase login && firebase use <your-project-id>
   (cd functions && npm install)
   firebase deploy --only firestore:rules,functions
   ```
4. **Point the Android SDK path**: create `local.properties` with
   `sdk.dir=/path/to/Android/sdk` (not committed).

## Build & install the APK

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install on **both** phones. Then on each phone:

1. Open Kin, walk through onboarding, grant camera / mic / notifications.
2. Grant the Health Connect read permissions (install Health Connect if prompted).
3. Approve the special-access nudges: display-over-other-apps, ignore battery
   optimization, full-screen notifications — these let the emergency call show
   over the lock screen and survive Doze.
4. On the pairing screen, each phone shows its own code. Enter **the other
   phone's** code and confirm. Done — vitals start syncing and either side can
   reach in.

## Reach-in modes (Settings)

- **Self-answer** — recommended; nothing to enable beyond onboarding.
- **Accessibility auto-answer** — turn on the switch, which deep-links to
  Android's Accessibility settings so you can enable the Kin service. Only then
  will it auto-tap answer.

## Security & privacy

- Anonymous Firebase Auth; only your **mutually-paired** partner can read your
  vitals or start a reach-in (enforced by `firestore.rules`).
- Every reach-in shows the camera indicator and a persistent call notification.
- No analytics, no third parties beyond your own Firebase project.

## Production notes

- **TURN server**: signaling uses Google's public STUN only. For reliable calls
  across mobile networks/NATs, add a TURN server (e.g. coturn) to the WebRTC
  ICE config in `call/webrtc/WebRtcSession.kt`.
- **QR pairing**: the pairing screen shows a copyable code; a scannable QR could
  be added (no QR lib is bundled).
- **Fall/SOS**: turn on your watches' built-in fall detection + emergency SOS
  today — that's independent of this app and worth having regardless.
