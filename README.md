# Enigma — private text and file encryption

Enigma is the next version of QuietKey: an offline Android app with a dark security theme, device setup, trusted groups and encryption for a specific recipient. Android 8.0/API 26 or newer. No account, server, Internet permission or VPN tunnel.

## Download and update

**[Download Enigma.apk](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk)** — the installable app is at the top of the **main** branch. Sign in, open the file, then choose **Download raw** (the download arrow). Open the APK on your phone and allow installation from that browser/file manager. It appears as **Enigma (test)**.

Install over the existing QuietKey test app to retain its keys and groups. Do not uninstall or clear storage. The app ID, vault format and Keystore aliases are retained. Both participants need version 0.2 or newer for new private messages and `.enigma` files. Old `QKM1:` messages remain readable with their original group; older clients cannot read new `QKD1:` messages.

The repository and release downloads are private. The source folder `app/` is not an installable app. This is an unaudited prototype; see [SECURITY.md](SECURITY.md) for its actual guarantees and limits.

## Set up your device

1. Name your device and choose **Set up with live camera**.
2. Allow camera access and move the camera for at least five seconds and 60 frames. The screen shows progress; no recording, audio or video preview is saved.
3. Android Keystore creates the installation's non-exportable private RSA key using system randomness. Camera frame hashes are mixed with additional system randomness to create a separate **Personal vault** group secret. Fresh text/file content keys mix that secret with fresh system randomness.
4. **Device** shows your public identity QR and full fingerprint. Only the public key is shared. **My device** lets you encrypt files/text for yourself before pairing.

Upgrading users can keep existing groups without camera setup and create their personal vault later from Device. There is no private-key export or recovery feature.

## Pair two devices

1. A: **Groups → Create a group**; enter its name and complete camera capture.
2. B: **Device**; show the public identity QR.
3. A: select the group, choose **Invite a new member**, scan B's QR, and compare the **entire fingerprint** with B's Device screen in person. Add an optional member name and confirm only if it matches.
4. A shows the invitation QR. B: **Groups → Join with invitation QR**; scan it, compare A's full fingerprint in person, and join.
5. In **Text** or **Files**, select that group and the specific **Private recipient**. Only locally verified members appear. New ciphertext can be opened by that recipient and the sender's own device.

For additional members the owner repeats the invitation flow. Other members use **Trust another member's public QR** to verify one another before exchanging private packets. Member lists and names are local to each device; this is not a centrally synchronized roster. Trusting a QR registers a public key but does not issue a group invitation.

**Groups** provides selection, renaming, member aliases, full fingerprints, a Write privately action, local trust removal and local group removal. Only the owner can issue invitations through the official client. Local removal does not revoke keys already held by another device; create a fresh group and invite remaining members when revocation is needed.

## Encrypt text or files

- **Text:** select group and recipient, enter a message, encrypt, then copy/share its `QKD1:` packet through your usual messenger. The receiver pastes it into Text with that group selected. The signature, recipient and authentication are checked, and the replay record is saved before plaintext is shown. Repeated message opening is rejected.
- **Files:** choose any image, document, video or other file up to 50 MiB. Save/share the opaque `.enigma` package. The original file is left in its location. Names and file types are encrypted inside the package; very long names are shortened to 120 characters.
- **Restore:** choose the group and open a received `.enigma` file. The app verifies the whole signed package and every encrypted chunk before offering **Save restored file**. That action saves a readable file to your chosen location. Previously opened archives may be restored again explicitly and are marked as previously opened.

Enigma controls who can decrypt the package. You choose the delivery contact in the messenger's share screen; it cannot bind an outside app's contact to an Enigma fingerprint. No plaintext copy/share buttons are provided. Drafts and displayed plaintext clear on backgrounding. Saved readable files and the original source remain outside the app's protected vault.

## Build and test locally

Use JDK 17, Android SDK Platform 35, Build Tools 34.0.0 and the included checksum-pinned Gradle 8.9 wrapper. Android Gradle Plugin is 8.7.3. On this PC the ignored `.local-tools/` JDK and `local.properties` are already configured.

```powershell
.\tools\dev.ps1 build
.\tools\dev.ps1 protocol
.\tools\dev.ps1 devices
.\tools\dev.ps1 install -Serial YOUR_PHONE_SERIAL
.\tools\dev.ps1 device-tests -Serial YOUR_PHONE_SERIAL
```

Build output: `app/build/outputs/apk/debug/app-debug.apk`. The test app remains `app.quietkey.debug`; production ID remains `app.quietkey`. Keep the local debug signing key for compatible updates. Do not use Gradle's `connectedDebugAndroidTest` on an installation you need to keep: it uninstalls the app afterward. The helper installs updates and runs instrumentation directly to preserve the phone's vault.

See [TESTING.md](TESTING.md) for physical-phone steps and [VERIFICATION.txt](VERIFICATION.txt) for recorded evidence. Desktop protocol, Keystore, camera setup, navigation and real Android file-picker round trips have been checked on a disposable Android 15 emulator. Physical-phone camera/QR pairing remains to be tested.
