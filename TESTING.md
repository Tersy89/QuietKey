# Enigma local setup and phone testing

Source and test APK are shared in the owner's private GitHub repository. Keep device logs, SDK paths, local tools and signing keys local; they are excluded from version control. The original archive had no TESTING.md; this guide was added during development.

## This PC

Open the editable **QuietKey** directory in Android Studio. Gradle 8.9 needs JDK 17: choose the checksum-verified JDK under ignored `.local-tools/` in Settings → Build, Execution, Deployment → Build Tools → Gradle. The installed Studio runtime is JDK 25 and should not run this Gradle build. SDK Platform 35 and Build Tools 34.0.0 are installed; ignored `local.properties` points to this PC's SDK.

```powershell
.\tools\dev.ps1 build
.\tools\dev.ps1 protocol
.\tools\dev.ps1 devices
```

If script execution is blocked, use a process-only invocation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\dev.ps1 build
```

`build` runs JVM tests, APK compilation and lint. `protocol` runs the original dependency-free 21 checks and UTF-8 Java syntax parsing. New direct-message/file tests run in the Gradle JVM suite. On another PC install JDK 17 and Android SDK, set JAVA_HOME/ANDROID_HOME, then run `gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest`.

## Install on your phone

1. Use Android 8.0+ and a data-capable USB cable. Enable Developer options by tapping Build number seven times, then enable USB debugging.
2. Connect the unlocked phone and authorize this PC in Android's prompt.
3. Run `.\tools\dev.ps1 devices`. Its serial must show `device`; unlock/authorize for `unauthorized`, or check the cable/USB driver for an empty list.
4. Build and run the following with that serial:

```powershell
.\tools\dev.ps1 install -Serial YOUR_PHONE_SERIAL
.\tools\dev.ps1 device-tests -Serial YOUR_PHONE_SERIAL
```

You can instead download root **Enigma.apk** from the private main branch and open it on the phone. It appears as **Enigma (test)**, app ID `app.quietkey.debug`, and updates the former QuietKey test install. Manual installation does not run instrumentation tests.

**Keep your installation and signing key.** Do not uninstall, clear app data or delete Keystore aliases to fix an installation you need. There is no key recovery. Another PC's debug signature may differ: do not uninstall to bypass a signature mismatch.

## Automated Android checks

The helper builds and installs both APKs with `adb install -r`, then runs instrumentation directly. It preserves the user's vault. The two Keystore tests use their own temporary vault/cache contexts and verify non-exportable RSA keys, signature/OAEP interoperability, old/new text, binary files, persisted aliases/setup/history and corrupted-vault rejection. The UI test is skipped on normal phone runs.

**Do not run `connectedDebugAndroidTest` on your phone's retained installation.** Gradle's connected suite uninstalls the target afterward, losing its keys. It is suitable only for a disposable emulator. Direct helper results are printed in the terminal; Gradle emulator results are in `app/build/reports/androidTests/connected/`.

Opt-in disposable emulator UI test (fresh installation, Android 10+, generic SDK model required; it expects the first-run setup screen):

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.enigmaUiTest=true'
```

This test names the emulator device, performs real emulated-camera setup, checks four-tab navigation, mandatory recipient selection, unified encrypted history, TTL selection, guide/reset cancellation, background clearing and FLAG_SECURE, and encrypts/saves/reopens/restores a 200,000-byte binary file through Android's actual document picker. Test previews and generated data are exported to the emulator's Download directory for local inspection. It does not alter the app's screenshot flags or run on ordinary phones.

## One-phone checklist

Use disposable messages and files first.

- Set a device name and complete camera setup. Confirm it waits at least five seconds of live input and 60 frames. Test denial and cancellation paths. Device shows a stable public fingerprint after restart.
- Pair a second verified device first. Home defaults to Choose verified recipient, and sending is disabled even when another member is available until explicitly selected. Choose that member and encrypt Unicode text such as `Hello 👋 ä 中文`; copy/paste the complete QKD2 packet and decrypt. A second opening is rejected, including after restart.
- Change a character in another encrypted packet; it must fail without plaintext. Display plaintext or write a draft, background the app and return: both clear.
- Encrypt an image, PDF and arbitrary binary file using Home or Messages attachments. Save/share the `.enigma` package and confirm the original remains. Reopen and restore it to a chosen location; compare the bytes/file hash. Restore again and confirm the previously-opened indication. Test empty and 50 MiB boundary files, oversized, truncated and tampered packages.
- Rename a group and device; restart and check persistence. Manage members shows names and full fingerprints. Remove disposable local groups with confirmation.
- Check screenshots are blocked in app and scanner, airplane-mode encryption works, and larger font/display settings keep controls reachable. Keyboard should not cover the only route to an action.

## Two/three-phone checklist

Compare full fingerprints in person. Follow README pairing steps, update every phone to 0.3+, and verify:

1. A owns a group; B joins its recipient-specific invitation. A/B send new private text/files in both directions.
2. Only A has invitation controls. A invites C; B and C explicitly trust each other's public QR to populate their local maps.
3. A sends to B. C, despite holding the same group seed, cannot decrypt that text/file. A can open its own sent copy. B cannot decrypt a packet intended only for C.
4. Unknown senders, nonmember recipients, wrong groups, changed signatures, expired groups and replayed text are rejected. Other local member lists do not automatically change when one device changes an alias or removes trust.
5. Removing C's local trust stops sending/receiving with C here, but does not invalidate C's retained keys. Create a new group and invite only A/B for actual group replacement.
6. A 0.1 QKM1 packet remains readable in the retained original group and is labeled legacy shared-group text. New QKD2/file-v2 .enigma packets require 0.3 on both ends.

Physical camera/QR pairing, vendor-specific Keystore behavior, actual update retention on the user's phone and accessibility across font/device sizes remain manual checks. See VERIFICATION.txt for executed evidence.

## 0.3 expiry, history and reset checks

- Confirm Home is a quick group/recipient launcher; Groups handles management; Messages lists prepared/received text and files together across groups; Device holds app info, guide, setup and reset.
- No My device fallback: before choosing another verified member, text/file encryption buttons are disabled. Choose a recipient explicitly, change group and check it becomes unselected again. Missing, self and unknown recipients must be rejected without creating a package.
- Choose a TTL before encryption. Send text and a file; check displayed authenticated expiry. After expiry, neither recipient nor sender can reopen them in the official app, including saved history. Verify expiry during the Save restored file picker is rejected. Correct device clocks are required. Saved readable copies remain outside this restriction.
- Close and restart the app: new history entries persist encrypted. Open a local text history entry again; an external repeat of an already consumed packet still fails. Prepared entries do not imply delivery. File archives can be explicitly restored again before expiry.
- On a DISPOSABLE installation only, Device → Reset app & delete local data. Empty/wrong confirmation must preserve data; Cancel must preserve data. Type RESET and confirm: app closes, camera permission is revoked, prior vault/history/packages are gone and the next device fingerprint differs. Exported files outside the app remain. Do not perform this test on an installation whose keys you need.
- Automated full-reset harness is opt-in on the generic disposable emulator only: enigmaUiTest=true plus enigmaResetTest=true. Reset intentionally kills its instrumentation process; a subsequent Keystore test with the recorded beforeIdentity verifies a new identity and absent retained vault/packages. It is never used by the normal phone helper.

Old QKD1 text and file-v1 packages were checked against packets generated by the actual 0.2 code. They open with no retroactive TTL. Both devices need 0.3 for newly created packets.
