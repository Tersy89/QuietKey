# Enigma 0.4.2 local setup and phone testing

Keep keys, SDK paths, device data/logs and previews local; they are ignored. Source and root APK are in the owner's private GitHub repository. VERIFICATION.txt records executed tests; SYSTEM_DESIGN.md describes the current implementation.

## This PC and build

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

Alternatively sign into GitHub and download root [Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk), then open/install it. It is Enigma (test), app.quietkey.debug, and uses the previous debug signer. Update both pairing devices to 0.4.2. Manual installation does not run instrumentation tests.

Use disposable development data. To retain an installation, install over it and keep the signing key; uninstall/clear-data loses its keys. Do not uninstall to bypass a signing mismatch. Old data/format compatibility is not a supported release commitment.

## Automated Android checks

The device-tests helper installs with adb install -r and runs instrumentation directly. Default Keystore signing/OAEP/file/vault tests use isolated file contexts and preserve the user's stored vault. Password-vault, UI, QR and biometric tests are explicitly skipped unless running the opted-in generic disposable emulator.

Do not run Gradle connectedDebugAndroidTest on a retained phone installation: it uninstalls the target afterward. Use it only on an identified disposable emulator with a fresh app installation:

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.enigmaUiTest=true'
```

The UI test covers password creation/unlock, live emulated-camera setup, tabs, explicit recipients, text TTL/replay/history, background/manual lock, fresh authentication for readable export, guide/reset cancellation, FLAG_SECURE and a 200,000-byte file through Android's actual picker. It also imports a software-owner invitation addressed to the actual Keystore identity, verifying group details/all public keys and retained history. The import uses the actual Paste invitation dialog/Continue action, with an injected clipboard-style packet typed into its field; it does not simulate two physical cameras scanning cycling QR screens. It also resumes the exact stored owner invitation after Activity/vault recreation, removes the pending member and verifies cache cleanup/tombstone retention, and tests Start again plus scanner cancellation returning to instructions. QrScanTest additionally decodes every generated request/invitation frame at 960, 720, 480 and 320 pixels, then passes those decoded strings through the production scanner collector/result boundary. This verifies generated images and reassembly, not a physical camera. QR frame/protocol handling also has separate JVM tests. QrImageTest covers 24 seeded frame payloads at four native sizes, using normal finder detection; it never uses PURE_BARCODE. JoiningFlowTest exercises interrupted/cancelled/deleted grants, old uncached used IDs, wrong group/nonowner/removed member rejection, renewed requests, obsolete receipts and changed directories. QrLumaTest covers padded rows, pixel stride, offset/read-only buffers and invalid bounds. Test previews/fixtures go to the disposable emulator's Download directory.

The password-vault test checks device-bound encryption, wrong password/key/corrupt vault/envelope failures, closed-session key clearing and password replacement without changing stored groups. It is emulator-only because changing password protection disables the app's shared biometric alias.

## Opt-in emulator camera and fingerprint regressions

Use only the disposable generic EnigmaTest emulator. The camera test uses the emulator's imagefile camera mode, so the actual camera preview/decoder/callback must return the fixture packet without injecting decoded text. It does not establish autofocus or screen-to-camera performance on Huawei/Samsung phones.

1. Build/install both APKs. Run QrScanTest#cameraPipelineDecodesQr with enigmaQrCameraTest=true and enigmaQrFixtureOnly=true. This writes a padded 527-character production QR fixture and deliberately skips camera validation.
2. Pull /sdcard/Android/data/app.quietkey.debug/files/qr-camera-test.png to a local ignored file. Restart the emulator using -camera-back imagefile:ABSOLUTE_FIXTURE_PATH. Wake/unlock it, then run QrScanTest with enigmaQrCameraTest=true and without the fixture-only flag. cameraPipelineDecodesQr and cameraXPipelineDecodesQr must each return the exact packet. cameraAutomaticallyFallsBackWhenNoQrIsRead configures only the legacy test decoder for CODE_128 so it cannot recognize the QR; after 12 seconds, the production fallback must switch to CameraX and decode it through the camera. No decoded packet string is injected. The fixture padding/offset compensates this emulator camera projection; it does not change production QR rendering.
3. For fingerprint testing, enroll fingerprint 1 with an emulator screen PIN in Android Settings. Keep the disposable vault from setupCameraAndNavigation (password Emulator passphrase 04). Run EnigmaUiTest#biometricEnableCancelAndUnlock with enigmaBiometricTest=true. Release any held emulator finger before starting. At BIO_ENABLE_READY and BIO_UNLOCK_READY, issue adb -s emulator-5554 emu finger remove, then emu finger touch 1 within 25 seconds; a held finger does not create a new authentication event. Missing host input deliberately fails the test. The test verifies passphrase-confirmed enable, cancellation without vault access, authenticated unlock and passphrase-confirmed disable. The retry waits for the real unlock button to become accessible after the cancelled system prompt closes.

These methods are skipped by the preserving phone helper. Run the full first-use UI test only after intentionally clearing the identified disposable emulator's app data. Do not clear or uninstall a retained phone to prepare tests.

## Password and one-phone checks

- First use: choose/repeat at least 12 characters; toggle Show passphrase to check typing and hide it again, name device, complete five-second/60-frame camera setup. Deny/cancel camera and retry. Restart: require password before any group/history is visible; fingerprint remains stable.
- Wrong password refuses access and delays retries; correct password unlocks. Background and screen-off lock the app and clear plaintext/drafts. Camera-setup/file-picker handoffs continue for at most two minutes; QR scanning allows at most five minutes; longer waits must require unlock and cancel stale results.
- Change password using the current password. Old password must fail; new password preserves keys/groups/history and disables biometric unlock. In Device → Security & unlock, on a supported strong enrolled device enable biometric unlock using fresh password; verify cancel/failure does not unlock. Change enrollment and confirm password remains available.
- Home initially has no selected recipient. Sending stays disabled until another approved member is explicitly selected, including after switching groups. Missing/self/unknown recipients must not create output.
- Send Unicode text, paste/decrypt the complete QKD3 packet. External replay fails; explicit local history reopening succeeds before expiry. Tampering fails without plaintext. TTL defaults to No expiry; exercise expiring text/files and expiry while the Save picker is open.
- Encrypt/restore images, PDFs and arbitrary binary files; compare bytes/hash and preserve sources. Test empty, 50 MiB boundary,oversized,truncated/tampered packages. Readable export requires the current app password even after biometric unlock; saved readable copies remain outside the vault.
- Inspect tabs, group/member names/fingerprints, history after restart, screenshot blocking, airplane mode, larger font/display and keyboard accessibility. No network connection is required for cryptography.

## Two/three-phone guided joining

Use correctly set clocks and update every device to 0.4.2. Pair directly with known people's devices.

1. A alone creates a group. B taps Join group and shows step 1. A opens the group, taps Invite new recipient → Scan joining QR, collects B's request frames and confirms. B chooses Scan owner's invitation and collects A's step 2 frames. Joining is complete when the group appears on B; no reverse invitation is needed. Verify B imports the correct group and all member keys without additional trust scans.
2. Send A↔B private text/files. A can open its sent copy. Optional B confirmation or verified text reply changes A's status from Invited to Joined.
3. Only A has invitation controls. A invites C. C imports A/B automatically; send C→B and B receives the owner-signed directory update automatically before accepting C's verified packet.
4. Photograph A's invitation: C must not be able to join with it. An invitation for an earlier B joining session must fail after B generates a new request. Expired,tampered,mixed-frame invitations must not commit partial groups/trust. Reimporting an existing group preserves its history and refuses duplicate joining.
5. A→B text/file must be unreadable on C despite C sharing the group secret. B cannot open content intended only for C. Wrong group secret must also prevent endpoint decryption of new formats. Unknown/blocked identities and forged owner directories must fail.
6. Interrupt after A displays its invitation. Close/reopen A, then Continue inviting. Verify B can finish the active request. Remove pending B on A, then retry B's old QR: reject with explicit Start again instructions. B creates a fresh request and A can readmit it. Repeat after B removes its local group: old consumed invitations remain rejected; a fresh request works without resetting either app.
7. On S23 test no-frame automatic fallback, manual Try other scanner, tap focus, torch and missing-frame progress. On P30 confirm the original path still works. Test Copy transfer → Paste request on A and Copy transfer → Paste invitation on B; reject wrong recipient, stale request, tampering, expired and duplicate group exactly as QR. Verify the known contact/owner when using messenger transport.
8. Local removal cannot revoke retained secrets on C. Create a replacement group and invite only A/B when testing actual group replacement. Offline members receive directory changes only with later packets.

## Destructive reset — disposable installation only

Device → Reset app & delete local data: require current passphrase, then exact RESET. Empty/wrong confirmation or Cancel preserves data. Valid reset closes the app and deletes its local keys/groups/history/setup/cache, revokes permissions and creates a different fingerprint on next setup. External original/exported/shared files remain. Forgotten-password reset is Android Settings → Apps → Enigma (test) → Storage → Clear data.

The emulator-only full-reset harness takes enigmaUiTest=true plus enigmaResetTest=true. Reset intentionally kills instrumentation; a subsequent Keystore test with recorded beforeIdentity checks a new identity. It is never part of the normal phone helper.

Physical-phone QR collection, vendor biometric prompts/invalidation, vendor camera/Keystore behavior, phone-update retention and accessibility remain manual checks unless separately recorded in VERIFICATION.txt. These tests are not an independent security audit.
