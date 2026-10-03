# QuietKey local setup and phone testing

This guide was added during local development because the supplied archive had no TESTING.md. Source and a test APK are shared privately on GitHub at the owner's request. Keep test results and device logs local; no public publishing or build scans are needed. Local tool downloads, SDK paths, build caches and signing keys are excluded from the repository.

## This PC

The project includes the Gradle 8.9 wrapper with a pinned distribution checksum. A checksum-verified JDK 17 is in `.local-tools/`; `tools/dev.ps1` finds it automatically. Android SDK Platform 35 and Build Tools 34.0.0 are installed in the existing SDK. `local.properties` contains this PC's SDK path and is excluded from version control.

Open the **QuietKey** directory in Android Studio. In Settings → Build, Execution, Deployment → Build Tools → Gradle, choose the JDK 17 under `.local-tools` as the Gradle JDK. The installed Studio runtime is JDK 25, which should not be used to run this project's Gradle 8.9 build.

From PowerShell in the QuietKey directory:

```powershell
.\tools\dev.ps1 build
.\tools\dev.ps1 protocol
.\tools\dev.ps1 devices
```

If PowerShell blocks the helper script, run it for this process only:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\dev.ps1 build
```

`build` runs JVM tests, builds the app and instrumentation APK, and runs Android lint. It does not run tests on a phone. The first build downloads dependencies; runtime needs no network connection. Debug app ID is `app.quietkey.debug`; it has a separate identity and vault from `app.quietkey`. Keep the local Android debug signing key to install updates over this test build.

On another PC, install JDK 17 and the Android SDK, set `JAVA_HOME` and `ANDROID_HOME`, then run `gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest`. Do not copy this PC's `local.properties`.

## Connect your phone

1. Use an Android 8.0 or newer phone and a USB cable that supports data.
2. In Settings → About phone, tap Build number seven times (some manufacturers place it under Software information).
3. In Developer options, enable USB debugging. Connect the unlocked phone and accept the USB debugging authorization prompt for this PC.
4. Run `.\tools\dev.ps1 devices`. The phone must show `device`. For `unauthorized`, unlock it and accept the prompt. For an empty list, try another cable/USB port; Windows may need the manufacturer's USB driver.
5. Build, then install:

```powershell
.\tools\dev.ps1 install
```

With multiple devices, pass `-Serial` using the identifier from `devices`:

```powershell
.\tools\dev.ps1 install -Serial YOUR_DEVICE_SERIAL
.\tools\dev.ps1 device-tests -Serial YOUR_DEVICE_SERIAL
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. You can also transfer it directly by USB and open it on the phone, allowing installation from that file manager when Android asks. Manual installation cannot run the automated instrumentation suite.

## Automated device checks

Run these before storing important data in a test installation:

```powershell
.\tools\dev.ps1 device-tests
```

The suite verifies actual Android Keystore signing and OAEP interoperability, non-exportable private keys, encrypted vault persistence and replay protection. Reports are under `app/build/reports/androidTests/connected/`. These checks do not verify the physical camera or QR pairing experience. Do not uninstall, clear app storage, delete groups or remove Keystore aliases to troubleshoot an installation whose keys you need; there is no recovery mechanism.

## One-phone smoke test

Use disposable messages and a group named "Local test".

- Launch QuietKey; identity preparation finishes without an error.
- Open Identity. A QR and a full fingerprint appear. Close and reopen the app; the fingerprint stays the same.
- Create a group, allow camera access, and move the phone. Capture must collect at least 60 frames over at least five seconds of live input. Cancel and deny-permission paths should return safely to the app.
- Select the group under Messages. Encrypt a Unicode message such as `Hello 👋 ä 中文`. Copy the encrypted `QKM1:` text, paste it back, and decrypt. The plaintext and verified sender appear.
- Paste that same packet again. The app rejects it as already decrypted. Close and reopen the app; it still rejects that packet.
- Encrypt another message; change a character in the packet and try decrypting. The app rejects it without displaying plaintext.
- Write a draft or display decrypted text, then press Home. Returning to the app shows cleared plaintext.
- Try a screenshot of the app and scanner; Android should block it or hide the protected content.
- Repeat with airplane mode enabled. Pairing and encryption must work offline.
- With the keyboard open and larger display/font settings, confirm message controls remain reachable and do not overlap the system bars.

## Two-phone pairing test

Follow README.md's pairing steps using two test installations. Compare **the entire fingerprint in person** before confirming either identity.

1. A creates a group. B shows its Identity QR; A scans it with Invite member via QR.
2. After matching fingerprints, A shows the invitation; B scans it with Scan invitation QR and verifies A's fingerprint.
3. A and B exchange encrypted text in both directions and check replay rejection after restarting both apps.
4. B's group view must show no invite control. An invitation for B must not work on a third device.
5. Cancel a fingerprint confirmation; no membership change should be saved.
6. Repeat pairing after backgrounding the scanner or recreating its parent activity; the app must resume correctly or report an interruption safely.
7. For a third member, each receiver also scans and verifies each other sender's Identity QR using Trust sender's identity QR.

Record the phone model, Android version, test step and exact error for failures. Use nonsensitive test content. Keep any logs local; device logs can contain identifying information.

## Verification limits

Desktop protocol tests do not establish Android Keystore, camera or scanner correctness. An APK build does not establish successful phone testing. Consult VERIFICATION.txt for checks actually completed. This remains an unaudited prototype; SECURITY.md documents its protocol and limitations.
