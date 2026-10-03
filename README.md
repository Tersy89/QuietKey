# QuietKey — offline Android message encryption

Native Android prototype (Java, Android 8.0/API 26+). No account, server, or network permission. Encrypt text here, send its `QKM1:` packet through any messenger, and paste it here to decrypt.

## Download the test APK

**[Download QuietKey.apk](https://github.com/Tersy89/QuietKey/blob/main/QuietKey.apk)** — the installable app is at the top level of the **main** branch, beside this README. Sign in to your GitHub account, open that file, and click **Download raw** (the download arrow). Open the downloaded APK on an Android 8.0+ phone and allow installation from your browser/file manager when Android asks. The app appears as **QuietKey (test)**.

The same verified APK is also available on the [test build download page](https://github.com/Tersy89/QuietKey/releases/tag/v0.1.0-debug.1) under Assets as **QuietKey-0.1.0-debug.apk**. The `app/` directory contains Android source; the source ZIP archives are not installable apps.

This test build passes the desktop checks and builds successfully. Real-phone, camera, QR scanner and Android Keystore tests are still pending. See TESTING.md for the checklist.

## Included behavior

- Each installation generates a public RSA identity with a non-exportable private key in Android Keystore.
- Up to 30 independent groups, each with a separate 256-bit shared seed, identity of its creator, and ten-year expiry.
- The creator captures a live camera stream for at least five seconds and 60 frames. Frames are hashed in memory and mixed with system `SecureRandom` output; no video or audio is recorded. The app shows capture progress rather than a video preview.
- Only the group creator can generate a group-key invitation, enforced in both the UI and the protocol code. Members can encrypt/decrypt and verify other sender identities.
- Recipient-specific, signed QR invitations: RSA-OAEP encrypts the shared seed for the recipient's public key. Photographing an invitation does not expose the seed without that device's private key.
- Authenticated AES-256-GCM messages, HKDF-SHA256 per-message keys, random message IDs and nonces, and RSA signatures verified against locally trusted public keys.
- Persistent replay detection; received message IDs are saved before plaintext is displayed. The replay log has a 100,000-message limit per group; create a new group at the limit.
- Android Keystore AES-GCM encrypts group seeds, creator identities, trusted keys, and replay logs in an atomically written local vault. App backups and device-transfer backup are disabled.
- Screenshot protection, plaintext cleared when backgrounded, keyboard-learning and autofill requests disabled on message fields. Only ciphertext has copy/share buttons.

## Pairing A and B

1. A: **Groups → Create group with camera**. Give the group a name, grant camera permission, and move the camera for five seconds.
2. B: **Identity**. Show the public identity QR to A.
3. A: **Invite member via QR** in the group. Scan B's identity QR and compare the entire fingerprint against B's identity screen in person. Confirm.
4. A shows the resulting invitation QR; B chooses **Groups → Scan invitation QR**.
5. B compares the creator's full fingerprint against A's identity screen, then joins.
6. Both select the group under **Messages**. Encrypt before sharing; paste the complete `QKM1:` packet to decrypt.

The creator repeats this flow for every additional member. For more than two participants, receivers must also use **Trust sender's identity QR** to scan and verify every other sender. That action registers a public key; it does not share the group seed. Group creators can see invitation controls; other members cannot.

## Build an APK

The original source archive did not include an APK, complete Gradle wrapper, or TESTING.md. This development copy now includes the standard Gradle 8.9 wrapper and a testing guide, and a debug APK has been built successfully. Source and APK are shared through a private GitHub repository at the owner's request.

On a development machine:

1. Install JDK 17, Android Studio, Android SDK Platform 35, and SDK Build Tools 34.0.0. The wrapper downloads Gradle 8.9. This PC is already configured for the PowerShell helper described in TESTING.md.
2. Open this directory in Android Studio. If needed, set `sdk.dir` in an untracked `local.properties` file or set `ANDROID_HOME`.
3. Use JDK 17 for Gradle (including Android Studio's Gradle JDK setting).
4. Run `./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest` (Windows: `gradlew.bat`, or `.\tools\dev.ps1 build`).
5. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It appears as **QuietKey (test)** and uses `app.quietkey.debug`, a separate installation and vault from a release build. Install it on two test phones using Android Studio or `adb install -r`.
6. With a test emulator/device attached, run `./gradlew connectedDebugAndroidTest` to validate real Android Keystore behavior and vault persistence.

Pinned dependencies: Android Gradle Plugin 8.7.3, ZXing Android Embedded 4.3.0, AndroidX Activity 1.9.3, Kotlin BOM 1.8.22 (aligns transitive runtime libraries). Build requires fetching dependencies. Runtime operates offline.

## Verification performed here

`tools/test-protocol.sh` runs a dependency-free JVM protocol self-test and Java syntax parsing. All 21 protocol checks passed: pairing, creator preservation, non-owner invite rejection, creator private-key enforcement, invitation tampering, group independence, identity QR parsing, wrong recipient, Unicode, replay, bidirectional messaging, tampered signatures, wrong group, wrong seed/GCM authentication, unknown sender, expiry, size bounds, truncated/trailing packets, fresh randomness, and the RFC 5869 HKDF vector.

Local verification on 3 October 2026: all 21 protocol checks, Gradle JVM tests, debug APK and instrumentation APK compilation, and Android lint passed (lint: zero errors, 20 warnings). The debug APK signature verifies; inspection of its merged permissions confirms no Internet or microphone permission and disabled backups. Camera startup now does not count toward the minimum five seconds of live capture; the capture screen also accounts for system bars and display density. The Windows syntax check explicitly uses UTF-8.

Phone tests have not run because no device is connected. Camera/scanner operation, actual Keystore interoperability and the expanded instrumentation suite remain unverified on a device. The device test uses its own temporary vault file rather than changing the user's groups, and checks durable replay rejection and tampered-vault rejection. See `TESTING.md` for setup and the phone checklist, `VERIFICATION.txt` for recorded status, and `SECURITY.md` for protocol details and limits.
