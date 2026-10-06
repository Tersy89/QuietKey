# Enigma 0.4.5 local setup and phone testing

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

Alternatively sign into GitHub and download root [Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk), then open/install it. It is Enigma (test), app.quietkey.debug, and uses the previous debug signer. Update both devices to 0.4.5. For a change still in review, download the root APK from that PR's branch. Manual installation does not run instrumentation tests.

Use disposable development data. To retain an installation, install over it and keep the signing key; uninstall/clear-data loses its keys. Do not uninstall to bypass a signing mismatch. Old data/format compatibility is not a supported release commitment.

## Automated Android checks

The device-tests helper installs with adb install -r and runs instrumentation directly. Default Keystore signing/OAEP/file/vault tests use isolated file contexts and preserve the user's stored vault. Password-vault, UI, QR and biometric tests are explicitly skipped unless running the opted-in generic disposable emulator.

Do not run Gradle connectedDebugAndroidTest on a retained phone installation: it uninstalls the target afterward. Use it only on an identified disposable emulator with a fresh app installation:

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.enigmaUiTest=true'
```

The UI test covers password creation/unlock, live emulated-camera setup, tabs, explicit recipients, text TTL/replay/history, background/manual lock, fresh authentication for readable export, guide/reset cancellation, FLAG_SECURE and a 200,000-byte file through Android's actual picker. It also imports a software-owner invitation addressed to the actual Keystore identity, verifying group details/all public keys and retained history. The import uses the actual Paste invitation dialog/Continue action, with an injected clipboard-style packet typed into its field; it does not simulate two physical cameras scanning each other’s screens. It also resumes the exact stored owner invitation after Activity/vault recreation, removes the pending member and verifies cache cleanup/tombstone retention, and tests Start again plus scanner cancellation returning to instructions. Saved invitation/QR confirmation controls are now inside the invitation method chooser. QrFlowUiTest opens actual full-screen request/invitation windows, decodes their rendered ImageView pixels, requires a square QR covering at least 80% of screen width and fully visible without clipping, checks a 3.5-second hold never advances the code, clicks every Next code and verifies the separate swap/finish screens. Run it at default display settings and at 720×1280/density 320 with font scales 1.3 and 1.5. Scanner collector tests reject advancement while paused and preserve collected codes when changing cameras. QrScanTest additionally decodes every generated request/invitation frame at 960, 720, 480 and 320 pixels, then passes those decoded strings through the production scanner collector/result boundary. This verifies generated images and reassembly, not a physical camera. QR frame/protocol handling also has separate JVM tests. QrImageTest covers 24 seeded frame payloads at four native sizes, using normal finder detection; it never uses PURE_BARCODE. JoiningFlowTest exercises interrupted/cancelled/deleted grants, old uncached used IDs, wrong group/nonowner/removed member rejection, renewed requests, obsolete receipts and changed directories. QrLumaTest covers padded rows, pixel stride, offset/read-only buffers and invalid bounds. Test previews/fixtures go to the disposable emulator's Download directory.

The password-vault test checks device-bound encryption, wrong password/key/corrupt vault/envelope failures, closed-session key clearing and password replacement without changing stored groups. It is emulator-only because changing password protection disables the app's shared biometric alias.

## Opt-in emulator camera and fingerprint regressions

Use only the disposable generic EnigmaTest emulator. The camera test uses the emulator's imagefile camera mode, so the actual camera preview/decoder/callback must return the fixture packet without injecting decoded text. It does not establish autofocus or screen-to-camera performance on Huawei/Samsung phones.

1. Build/install both APKs. Run QrScanTest#cameraPipelineDecodesQr with enigmaQrCameraTest=true and enigmaQrFixtureOnly=true. This writes a padded 527-character production QR fixture and deliberately skips camera validation.
2. Pull /sdcard/Android/data/app.quietkey.debug/files/qr-camera-test.png to a local ignored file. Restart the emulator using -camera-back imagefile:ABSOLUTE_FIXTURE_PATH. Wake/unlock it, then run QrScanTest with enigmaQrCameraTest=true and without the fixture-only flag. cameraPipelineDecodesQr and cameraXPipelineDecodesQr must each return the exact packet. cameraManualAlternateDecodesQr configures only the original test decoder for CODE_128 so it cannot recognize the QR, then explicitly selects CameraX and decodes through the camera. Each actual camera test must show Code scanned before an explicit Continue returns the packet. No decoded packet string is injected. The fixture padding/offset compensates this emulator camera projection; it does not change production QR rendering.
3. For fingerprint testing, enroll fingerprint 1 with an emulator screen PIN in Android Settings. Keep the disposable vault from setupCameraAndNavigation (password Emulator passphrase 04). Run EnigmaUiTest#biometricEnableCancelAndUnlock with enigmaBiometricTest=true. Release any held emulator finger before starting. At BIO_ENABLE_READY and BIO_UNLOCK_READY, issue adb -s emulator-5554 emu finger remove, then emu finger touch 1 within 25 seconds; a held finger does not create a new authentication event. Missing host input deliberately fails the test. The test verifies passphrase-confirmed enable, cancellation without vault access, authenticated unlock and passphrase-confirmed disable. The retry waits for the real unlock button to become accessible after the cancelled system prompt closes.

These methods are skipped by the preserving phone helper. Run the full first-use UI test only after intentionally clearing the identified disposable emulator's app data. Do not clear or uninstall a retained phone to prepare tests.

## Password and one-phone checks

- First use: choose/repeat at least 12 characters; toggle Show passphrase to check typing and hide it again, name device, complete five-second/60-frame camera setup. Deny/cancel camera and retry. Restart: require password before any group/history is visible; fingerprint remains stable.
- Wrong password refuses access and delays retries; correct password unlocks. Background and screen-off lock the app and clear plaintext/drafts. Camera-setup/file-picker handoffs continue for at most two minutes; QR scanning allows at most five minutes; longer waits must require unlock and cancel stale results.
- Change password using the current password. Old password must fail; new password preserves keys/groups/history and disables biometric unlock. In Device → Security & unlock, on a supported strong enrolled device enable biometric unlock using fresh password; verify cancel/failure does not unlock. Change enrollment and confirm password remains available.
- Home with only this device has no recipient and disables sending. A group with exactly one other member selects that member visibly and enables sending; multiple other members require a choice, including after switching groups. Missing/self/unknown recipients must not create output.
- Send Unicode text. Select Starter group on B, then Messages → Open received text → Paste and open: it must find the joined group and open the verified reading view. Try line-wrapped packets and Share → Enigma from a messenger while locked/unlocked. There must be no receive group/recipient/TTL selector. Reply must select the authenticated sender. External replay fails; explicit local history reopening succeeds before expiry. Tampering, missing groups and incomplete packets fail without plaintext. TTL defaults to No expiry; exercise expiring text/files and expiry while the Save picker is open.
- Encrypt/restore images, PDFs and arbitrary binary files; compare bytes/hash and preserve sources. Test empty, 50 MiB boundary,oversized,truncated/tampered packages. Readable export requires the current app password even after biometric unlock; saved readable copies remain outside the vault.
- Inspect tabs, group/member names/fingerprints, history after restart, screenshot blocking, airplane mode, larger font/display and keyboard accessibility. No network connection is required for cryptography.

## USB and personal-hotspot joining — priority phone checks

Update both phones to 0.4.5. Owner creates a group and selects Invite new recipient. Recipient chooses Groups → Join group. Personal hotspot (recommended), USB cable and QR fallback appear in that order.

1. **USB:** open USB joining on both, connect a known data cable, let one phone control USB and approve Android's USB/accessory prompts. Check owner Invite and recipient Join group confirmations, then both success screens. B imports the group/secret/all public keys; A records Joined and clears the pending grant. Repeat with the other phone hosting the USB bus and with an OTG adapter if needed. Debugging must stay unnecessary. Charging-only/unsupported accessories should leave a useful waiting/error screen; use hotspot when unsupported.
2. **Hotspot:** enable A's personal hotspot in Android Settings; connect B using its Wi-Fi password, accepting No Internet. Open Personal hotspot on both. A must start waiting automatically, then show Ready and one compact connection card. B taps Scan owner's connection card: one scan must return and connect immediately without Next/Continue/Swap. Confirm Invite/Join on both and verify the signed receipt/group import. Copy/Paste connection card must also connect automatically; test manual code/address fallback. Open connection settings, go Back and verify joining reopens with a fresh request/code while unlocked. Done opens Home; a two-person group selects the other person automatically. Immediately send A→B and B→A Unicode text/files, including after restarting and while Starter group is selected on the receiver. Repeat with mobile data off and roles reversed. Check Use another local address, normal private Wi-Fi, client isolation, hotspot loss and gateway hints. No external service should be contacted.
3. **Negative cases:** wrong/short code, public/hostname/loopback address, cable detach, USB denial, owner Decline, recipient Decline, five-minute timeout, Cancel, background, screen-off and Activity recreation. Reopen after interruption; it creates a fresh request/code without resetting identity/history. No partial/declined recipient import; an owner-approved pending member may remain Invited after a declined/lost recipient transfer. Remove that member if unwanted. If the group already imported before lost confirmation, retain it; the first verified message confirms it later. Reimport must refuse existing groups while preserving history.
4. **Security:** tampered/truncated/downgraded/oversized/out-of-sequence transport, wrong-recipient/stale invitation, forged receipt and false owner directory must fail. C's key cannot decrypt B's invitation or A↔B content despite sharing the group seed. Confirm visible device/owner fingerprints with the known person; neither network membership nor an AOA string authenticates a name. Do not send the temporary code to an unknown person.
5. **UI:** no scanner opens automatically; B may explicitly choose the one-card scanner for hotspot setup. Check the card at normal and enlarged fonts, manual code/address and keyboard, visible Invite/Join/Done, and the verified reading view/Reply on P30/S23. About me guide must explain current steps. Cancel/bad input must preserve the vault. USB receiver/listener/sockets should cease on lock and leaving the flow.

ReceiveUiTest is opted-in emulator-only. It uses an actual Keystore recipient with a software-owner signed invitation, then exercises wrong-selected-group routing via Paste and open, durable replay rejection after recreation, ACTION_SEND waiting for unlock and surviving pending-state recreation, and a 200k encrypted file's automatic group selection/restored bytes. These are actual app UI/vault/Keystore checks with software peers, not two-phone delivery evidence. NearbyJoinTest also decrypts the first owner message immediately after actual Android TCP joining with Starter group selected. ConnectionCardTest decodes a rendered 320-pixel one-card QR and rejects malformed/public/DNS/short-secret endpoints. IncomingTextTest reproduces wrong-Starter-group failure, line wrapping, private-recipient/signature/replay protections and legacy routing. LocalJoinTest verifies bidirectional private messages after the duplex exchange.

LocalJoinTest uses ordinary JVM TCP streams with software RSA peers: encrypted/fragmented duplex exchange, signed admission/import/receipt, wrong-code/tamper/downgrade/bounds/truncation/replay/address guards, invalid signature, decline and approval timeout. Fragmented plain streams exercise framing, not physical USB or Android AOA. NearbyJoinTest is opted-in generic-emulator-only: actual Android TCP listener/client and MainActivity consent/persistence with a software peer, recipient import/decline, automatic signed confirmation, preferred-method order, USB waiting/receiver startup and background cancellation. A separate emulator test sends actual PendingIntent fill-in grant/denial extras and verifies cancellation on close; this emulates the result delivery, not a hardware permission prompt. The settings-return test opens actual Android connection settings, goes Back and checks the handoff allowance ends while groups/history remain preserved. Its previews use the actual rendered Dialog. These checks do not establish Samsung/Huawei USB/hotspot interoperability. The preserving phone helper skips them without enigmaUiTest=true; never opt a retained phone into disposable-emulator tests.

## QR fallback — existing manual camera checks

Use correctly set clocks and update every device to 0.4.5. Pair directly with known people's devices.

1. A alone creates a group. B taps Join group → QR fallback and shows step 1. A opens the group and taps Invite new recipient → QR fallback. After each Code scanned, B taps Next code and A taps Scan next code. A continues and confirms Invite after the last code. B taps Swap phones → Scan owner's invitation, then scans A's codes using the same manual advances; after the last code B taps Join group. Joining is complete when the group appears on B; no reverse invitation is needed. Verify B imports the correct group and all member keys without additional trust scans.
2. Send A↔B private text/files. A can open its sent copy. Optional B confirmation or verified text reply changes A's status from Invited to Joined.
3. Only A has invitation controls. A invites C. C imports A/B automatically; send C→B and B receives the owner-signed directory update automatically before accepting C's verified packet.
4. Photograph A's invitation: C must not be able to join with it. An invitation for an earlier B joining session must fail after B generates a new request. Expired,tampered,mixed-frame invitations must not commit partial groups/trust. Reimporting an existing group preserves its history and refuses duplicate joining.
5. A→B text/file must be unreadable on C despite C sharing the group secret. B cannot open content intended only for C. Wrong group secret must also prevent endpoint decryption of new formats. Unknown/blocked identities and forged owner directories must fail.
6. Interrupt after A displays its invitation. Close/reopen A, then Invite new recipient → Continue QR invitation. Verify B can finish the active request. Remove pending B on A, then retry B's old QR: reject with explicit Start again instructions. B uses More → Start again to create a fresh request and A can readmit it. Repeat after B removes its local group: old consumed invitations remain rejected; a fresh request works without resetting either app.
7. Hold each code still; it must never advance on its own. Verify Code scanned pauses the camera and Scan next code resumes it. On P30/S23 check original AUTO focus, More → Try other scanner, alternate tap focus and torch. Check all joining text/buttons plus the full-width QR/camera area on normal and enlarged text settings. Test Copy transfer → Paste request on A and Copy transfer → Paste invitation on B; reject wrong recipient, stale request, tampering, expired and duplicate group exactly as QR. Verify the known contact/owner when using messenger transport.
8. Local removal cannot revoke retained secrets on C. Create a replacement group and invite only A/B when testing actual group replacement. Offline members receive directory changes only with later packets.

## Destructive reset — disposable installation only

Device → Reset app & delete local data: require current passphrase, then exact RESET. Empty/wrong confirmation or Cancel preserves data. Valid reset closes the app and deletes its local keys/groups/history/setup/cache, revokes permissions and creates a different fingerprint on next setup. External original/exported/shared files remain. Forgotten-password reset is Android Settings → Apps → Enigma (test) → Storage → Clear data.

The emulator-only full-reset harness takes enigmaUiTest=true plus enigmaResetTest=true. Reset intentionally kills instrumentation; a subsequent Keystore test with recorded beforeIdentity checks a new identity. It is never part of the normal phone helper.

Physical-phone QR collection, vendor biometric prompts/invalidation, vendor camera/Keystore behavior, phone-update retention and accessibility remain manual checks unless separately recorded in VERIFICATION.txt. These tests are not an independent security audit.
