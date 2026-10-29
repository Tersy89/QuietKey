# Enigma 0.6.0 local setup and phone testing

Keep keys, SDK paths, device data/logs and previews local; they are ignored. Source and root APK are in the owner's private GitHub repository. VERIFICATION.txt records executed tests; SYSTEM_DESIGN.md describes the current implementation.

## This PC and build

OneDrive can lock files under app/build mid-build. If Gradle reports AccessDeniedException there, build with an init script that moves the build directory outside OneDrive, e.g. `.\gradlew.bat -I $env:LOCALAPPDATA\quietkey-build\outside-onedrive.gradle ...` containing `allprojects { layout.buildDirectory = new File(System.getenv("LOCALAPPDATA"), "quietkey-build/" + project.name) }`.

Open the editable QuietKey directory in Android Studio. Use JDK 17 under ignored .local-tools/ for Gradle; the installed Studio runtime is JDK 25 and should not run Gradle 8.9. SDK Platform 35/Build Tools 34.0.0 are installed; ignored local.properties points to this PC's SDK.

```powershell
.\tools\dev.ps1 build
.\tools\dev.ps1 protocol
.\tools\dev.ps1 devices
```

If script policy blocks the helper, use powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\dev.ps1 build. The build runs JVM tests, app/instrumentation compilation and lint. Protocol runs the original dependency-free checks and UTF-8 Java syntax parsing. On another PC install JDK 17/Android SDK, set JAVA_HOME/ANDROID_HOME and run gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest.

## Install on the phone

1. Android 8+; enable Developer options and USB debugging, connect a data cable and authorize the PC while unlocked.
2. Run .\tools\dev.ps1 devices. The serial must show device; authorize for unauthorized or check cable/drivers for an empty list.
3. Build and install with that serial:

```powershell
.\tools\dev.ps1 install -Serial YOUR_PHONE_SERIAL
.\tools\dev.ps1 device-tests -Serial YOUR_PHONE_SERIAL
```

Alternatively sign into GitHub and download root [Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk), then open/install it. It is Enigma (test), app.quietkey.debug, and uses the previous debug signer. Update both pairing devices to 0.4.4. Manual installation does not run instrumentation tests.

Use disposable development data. To retain an installation, install over it and keep the signing key; uninstall/clear-data loses its keys. Do not uninstall to bypass a signing mismatch. Old data/format compatibility is not a supported release commitment.

## JVM unit tests

`gradlew testDebugUnitTest` runs the pure-Java tests, with no phone or emulator needed. On branch feature/0.7.0-messages, `SealedProtocolTest` covers the 0.7.0 protocol layer (one-time keys, multiple recipients, group binding, key pools and entropy mixing). That layer is not used by the app yet, so there are no phone checks for it yet.

## Automated Android checks

The device-tests helper installs with adb install -r and runs instrumentation directly. Default Keystore signing/OAEP/file/vault tests use isolated file contexts and preserve the user's stored vault. Password-vault, UI, QR and biometric tests are explicitly skipped unless running the opted-in generic disposable emulator.

Do not run Gradle connectedDebugAndroidTest on a retained phone installation: it uninstalls the target afterward. Use it only on an identified disposable emulator with a fresh app installation:

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.enigmaUiTest=true'
```

The UI test (EnigmaUiTest, updated for 0.6.0 but not yet re-run on the emulator) covers password creation/unlock, live emulated-camera setup, tabs, explicit recipients on Messages → Send, Send as text instead with TTL, pasted-text opening and external replay rejection, reopening from a conversation, background/manual lock clearing the draft, a 200,000-byte file sent as .enigma through Android's actual picker, saved for USB from the conversation, reopened through Receive → Open .enigma file and restored with fresh authentication, guide/reset cancellation and FLAG_SECURE. Sharing opens Android's share sheet, which locks Enigma; the test dismisses it and unlocks again. InboxTest (JVM) covers conversation grouping/order, labels, preview limits and a typed message round-tripping as a .enigma file. JoiningFlowTest, JoinBootstrapTest, LocalJoinTest and PairingProtocolTest cover joining; QrImageTest covers the single joining QR.

The password-vault test checks device-bound encryption, wrong password/key/corrupt vault/envelope failures, closed-session key clearing and password replacement without changing stored groups. It is emulator-only because changing password protection disables the app's shared biometric alias.

## Opt-in emulator fingerprint regression

Use only the disposable generic EnigmaTest emulator. The multi-frame QR camera regressions (QrScanTest) were removed with multi-frame QR joining in 0.5.0.

1. For fingerprint testing, enroll fingerprint 1 with an emulator screen PIN in Android Settings. Keep the disposable vault from setupCameraAndNavigation (password Emulator passphrase 04). Run EnigmaUiTest#biometricEnableCancelAndUnlock with enigmaBiometricTest=true. Release any held emulator finger before starting. At BIO_ENABLE_READY and BIO_UNLOCK_READY, issue adb -s emulator-5554 emu finger remove, then emu finger touch 1 within 25 seconds; a held finger does not create a new authentication event. Missing host input deliberately fails the test. The test verifies passphrase-confirmed enable, cancellation without vault access, authenticated unlock and passphrase-confirmed disable. The retry waits for the real unlock button to become accessible after the cancelled system prompt closes.

These methods are skipped by the preserving phone helper. Run the full first-use UI test only after intentionally clearing the identified disposable emulator's app data. Do not clear or uninstall a retained phone to prepare tests.

## Password and one-phone checks

- First use: choose/repeat at least 12 characters; toggle Show passphrase to check typing and hide it again, name device, complete five-second/60-frame camera setup. Deny/cancel camera and retry. Restart: require password before any group/history is visible; fingerprint remains stable.
- Wrong password refuses access and delays retries; correct password unlocks. Background and screen-off lock the app and clear plaintext/drafts. Camera-setup/file-picker handoffs continue for at most two minutes; QR scanning allows at most five minutes; longer waits must require unlock and cancel stale results.
- Change password using the current password. Old password must fail; new password preserves keys/groups/history and disables biometric unlock. In Device → Security & unlock, on a supported strong enrolled device enable biometric unlock using fresh password; verify cancel/failure does not unlock. Change enrollment and confirm password remains available.
- Messages → Send initially has no selected recipient. Encrypt & share and Send as text instead stay disabled until another approved member is explicitly selected, including after switching groups. Missing/self/unknown recipients must not create output.
- Send Unicode text, paste/decrypt the complete QKD3 packet. External replay fails; explicit local history reopening succeeds before expiry. Tampering fails without plaintext. TTL defaults to No expiry; exercise expiring text/files and expiry while the Save picker is open.
- Encrypt/restore images, PDFs and arbitrary binary files; compare bytes/hash and preserve sources. Test empty, 50 MiB boundary,oversized,truncated/tampered packages. Readable export requires the current app password even after biometric unlock; saved readable copies remain outside the vault.
- Inspect tabs, group/member names/fingerprints, history after restart, screenshot blocking, airplane mode, larger font/display and keyboard accessibility. No network connection is required for cryptography.

## Receiving fix — priority phone check

1. On both phones keep the Starter group. Join a new group, then on the receiving phone select **Starter group** before pasting a message encrypted in the new group. It must decrypt and switch to the new group.
2. Send a long message through a messenger that wraps text (or paste it with added line breaks). It must still decrypt.
3. Paste a message for a group not on this phone: the error must say to join that group first.

## Nearby joining — priority phone checks

Update both phones to 0.5.0 (installs over 0.4.4; same debug signer). Owner creates a group and taps Invite new recipient. Recipient taps Groups → Join group.

1. **QR, hotspot (S23 owner → P30 recipient, then reversed):** owner chooses Nearby: QR code or NFC tap and allows Nearby devices (13+) or Location (P30; Location must be on). Check the hotspot starts without opening Settings and one QR appears. Recipient chooses Scan or tap → Scan owner's code, then Connect in Android's prompt. Both screens must show the same six-digit check code; owner taps Codes match · Invite, recipient Join group. Both show success; owner shows Joined. Note whether the owner's normal Wi-Fi drops and returns.
2. **NFC (fixed in 0.6.0; 0.5.0 showed Android's "Empty tag"):** repeat with NFC on both phones, holding them back to back instead of scanning, until the check code appears. Repeat once after first opening and backing out of Scan QR code on the recipient. Neither phone may show Android's tag screen or lock Enigma. Note each phone's Android version (owner polling suppression needs Android 15+).
3. **Same Wi-Fi:** both on the home Wi-Fi; owner chooses Nearby on the same Wi-Fi.
4. **Messaging after joining:** send A→B and B→A text and a file in the new group; both must decrypt.
5. **Negative cases:** scan an expired code (wait five minutes), Cancel on either phone mid-join, owner Decline, recipient Decline, deny the permission, deny Android's Connect prompt, move out of range, background/screen-off during joining. None may save a group without confirmation; the hotspot must stop afterwards.
6. **Check-code race (optional):** with a third phone, scan the owner's QR first. The owner's check code must differ from the intended recipient's screen; Decline.

## Messages 0.6.0 — priority phone checks

1. Messages → Send: type a message, choose group and person, Encrypt & share → WhatsApp (or any app). On the other phone tap the received .enigma file → open with Enigma → unlock: the text shows in Enigma, from the right person and group.
2. Send a photo the same way. It must open as a picture inside Enigma, not save to the phone. Save to device asks for the passphrase and saves where you choose.
3. Send a PDF or other file: Enigma shows name/size and offers Save to device only.
4. Messages → Receive → Open .enigma file from Downloads/Files works the same. Send as text instead → paste on the other phone → Receive → Open pasted text.
5. Conversations: each person/group appears once on Messages, newest first; inside, sent items are on the right, received on the left; tapping reopens; sent items offer Share again and Save .enigma file (for USB).
6. Expiry: send with 5 minutes; an open message closes when it expires and can no longer be opened.
7. Background while a photo is open: Enigma locks and the photo is gone after unlock.

## Manual transfer — phone checks

1. Recipient: Join group → By file: save my request. Copy it to the owner over USB via a computer.
2. Owner: Invite new recipient → By file: open their request; note the check code; Invite → Save file. Copy the invitation back.
3. Recipient: open the owner's invitation file; the check code must match the owner's. Join; Save confirmation file; owner opens it and shows Joined.
4. Negative: open the wrong file type, an invitation meant for another phone, a request older than five minutes, a modified file. All must be refused.

## README screenshots

docs/screenshots comes from ScreenshotTest. It is opt-in and emulator-only, and needs a fresh installation. It seeds demo members (Sam, Jordan) and genuinely encrypted demo messages, then renders the real screens from inside the app, because FLAG_SECURE blocks screen capture. Run it on a disposable emulator only:

```powershell
adb install -r app-debug.apk; adb install -r app-debug-androidTest.apk
adb shell am instrument -w -e class app.quietkey.ScreenshotTest -e enigmaScreenshots true app.quietkey.debug.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/app.quietkey.debug/files/screenshots/. docs/screenshots/
```

## Destructive reset — disposable installation only

Device → Reset app & delete local data: require current passphrase, then exact RESET. Empty/wrong confirmation or Cancel preserves data. Valid reset closes the app and deletes its local keys/groups/history/setup/cache, revokes permissions and creates a different fingerprint on next setup. External original/exported/shared files remain. Forgotten-password reset is Android Settings → Apps → Enigma (test) → Storage → Clear data.

The emulator-only full-reset harness takes enigmaUiTest=true plus enigmaResetTest=true. Reset intentionally kills instrumentation; a subsequent Keystore test with recorded beforeIdentity checks a new identity. It is never part of the normal phone helper.

Physical-phone nearby joining (hotspot, Wi-Fi prompt, NFC, single-QR scanning), vendor biometric prompts/invalidation, vendor camera/Keystore behavior, phone-update retention and accessibility remain manual checks unless separately recorded in VERIFICATION.txt. These tests are not an independent security audit.
