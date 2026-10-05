# Enigma — private text and file messages

Offline Android encryption for a selected member of an owner-managed group. Android 8.0/API 26+. No account, server or Internet permission. The repository and APK distribution remain private.

## Download 0.4.2

**[Download Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk)**. Sign in, open the file and choose **Download raw**. Install it on both devices. It appears as **Enigma (test)**. This is an unaudited development prototype with no recovery; use disposable data while testing.

The app ID and debug signer are unchanged. Install over the existing test app if you want to retain its data. Older development formats are not a compatibility commitment. Update both pairing devices to **0.4.2** for the smaller QR frames, scanner fallback and invitation retry controls. QKD3 text and file-v3 remain the current message formats.

## Protect and set up your device

1. Choose and repeat an app passphrase of 12–128 characters. Use **Show passphrase** to check typing; it starts hidden each time. Several random words are preferable. This cryptographically protects the local vault, including group secrets, groups and history. There is no forgotten-passphrase recovery.
2. Name the device and complete live camera setup: at least five seconds and 60 frames. Android Keystore generates the private RSA identity; camera input contributes additional input to group-secret generation. No video or audio is saved. The initial **Starter group** contains only this device.
3. **Device → Security & unlock** provides **Lock Enigma now**, passphrase change, and optional fingerprint unlock on supported Android 8+ devices with strong enrolled biometrics. Biometrics are an alternate unlocking route, not an additional required factor. Changing the passphrase disables biometric unlock. If an existing biometric setup fails after this update, unlock with your passphrase, then disable and enable biometric unlock again.

The protected vault is the app's local storage, not a separate personal file-storage feature. Leaving the app locks it; screen-off always locks. Camera setup and system file pickers have a controlled allowance of at most two minutes; the QR scanner allows at most five minutes to collect a large transfer. Screen-off still locks immediately. Fresh passphrase entry is required before readable-file export, reset and security-setting changes.

## Join a group with two QR transfers

1. Only A creates the group. A is its owner; B joins that same group.
2. B taps **Groups → Join group**. **Step 1 of 2** shows B's joining QR. A opens the group → **Invite new recipient → Scan joining QR**, scans B's screen and confirms **Invite**.
3. A's **Step 2 of 2** shows the invitation encrypted only for B. B taps **Scan owner's invitation**, scans every frame, and sees **Joined [group]**. The group appearing on B means joining is complete. B does not invite A back.
4. **Invited** on A means awaiting B's first verified reply, not that B must repeat joining. The optional confirmation QR is behind **Show confirmation QR** on B's success screen.

QR masks are checked for normal finder detection before display. Codes cycle every 2.5 seconds. Keep the entire code visible about 20–40 cm away. Use **Pause / Previous / Next** to show missing frames. If no frame is read after 12 seconds, the app tries a second offline scanner automatically; **Try other scanner** also switches manually. CameraX supports tapping the preview to focus. **Light on** is available. The Samsung S23 still needs a physical retest of this fallback.

**Interrupted joining:** A reopens **Continue inviting [name]** in group details while the request is valid. B reopens **Join group** to continue the same request. Cancelling B's scanner returns to its request instructions. If the request expired, was already used in an older build, or the member was removed, B taps **Start again** and A invites that fresh QR. Deleting a member does not erase used-request protection. No app reset is needed.

**Camera fallback:** B can **Copy transfer** and send the complete joining request to A in their usual messenger. A chooses **Paste request** from Invite new recipient and confirms admission. A then **Copy transfer**s the encrypted invitation back; B chooses **Paste invitation** on the Join group screen. These are the same signed/recipient-bound packets as QR, with the same verification and five-minute expiry. Group secrets/private keys are never copied in plaintext. Verify the known person/owner when using an external messenger.

Pair directly with the known owner's device. A photographed invitation cannot be used with C's private key, but QR signatures alone do not prove a person's real-world identity. Member names and full fingerprints remain visible. Invitations expire within five minutes even when messages use No expiry.

The owner repeats this for each new device, up to 30 per group. Existing members receive owner-signed directory updates with new messages/files. Offline devices update when a packet reaches them. A device identity can join multiple groups, each with an independent secret.

## Four tabs and sending

- **Home:** quickly select a group and explicitly select another verified recipient, then write a message or send a file/image. Sending stays disabled until a recipient is chosen.
- **Groups:** create/join and choose a group. Its details contain invitations, member management, renaming and removal.
- **Messages:** prepared and received text/files together across groups. Explicitly open an entry to decrypt it. Prepared means encrypted locally, not delivered or read.
- **Device:** device name, app version, updated how-to guide, fingerprint, **Security & unlock**, and full reset.

Text and arbitrary files, including images/PDFs, are encrypted before Share/Copy/Save is offered. Choose the corresponding person in the external messenger. The sender can reopen their own copy. Missing, self or unregistered recipients are rejected by both UI and protocol.

New packets combine a fresh secret wrapped to the sender/recipient public keys with the group's secret to derive their content key. Reception needs both the endpoint private key and the correct group secret. Another member's private key plus the shared group secret cannot open A↔B private content. Signatures authenticate the sender; encryption provides confidentiality.

History keeps the latest 200 encrypted entries and up to 150 MiB of encrypted file packages. Text/history metadata and group secrets are inside the encrypted vault. File packages remain encrypted in private storage. Plaintext message/file bodies are not persisted in history. Original and restored files remain at the locations you choose.

## TTL and receiving

Choose **Message expiry (TTL)** before encryption: **No expiry** by default, five minutes, one hour, 24 hours, seven days or 30 days. It starts at encryption, is signed/authenticated, and is capped by group expiry. Enigma checks expiry on opening, history reopening and readable-file export. Group expiry is a separate ten-year policy.

TTL uses device clocks. It cannot erase saved plaintext or prevent a modified recipient from bypassing expiry. It is not remote self-destruction.

Paste a complete received text packet and decrypt it in its group. External repeated text opening is rejected after its ID is durably saved; explicitly reopening authenticated local history remains possible before expiry.

Open received `.enigma` files through Messages. The app verifies the complete signature and every chunk before offering **Save restored file**, which requires the app passphrase again. Files up to 50 MiB are supported; names and MIME types are encrypted. Archives may be explicitly restored again before expiry and are marked previously opened. Saving readable content places it outside Enigma's protection.

## Removal and reset

Removing local trust blocks that identity here but cannot revoke secrets already held elsewhere. Create a fresh group and invite remaining members for group replacement. Automatic rekeying and forward secrecy are not implemented. See [SECURITY.md](SECURITY.md) for limits.

**Device → Reset app & delete local data** requires the current passphrase and exact typed **RESET**. Android clears this installation's keys, groups, setup, history/cache, permissions and URI grants, then closes the app. The APK stays installed. Files outside the app remain. To start over after forgetting the passphrase, clear app data in Android Settings; this destroys the local identity/data.

## Build and test

JDK 17, SDK Platform 35, Build Tools 34.0.0, checksum-pinned Gradle 8.9 wrapper and AGP 8.7.3. This PC's SDK/tools are configured in ignored local files.

```powershell
.\tools\dev.ps1 build
.\tools\dev.ps1 protocol
.\tools\dev.ps1 devices
.\tools\dev.ps1 install -Serial YOUR_PHONE_SERIAL
.\tools\dev.ps1 device-tests -Serial YOUR_PHONE_SERIAL
```

Debug output: app/build/outputs/apk/debug/app-debug.apk; app ID app.quietkey.debug. Keep the same signing key for updates. Use the preserving device-tests helper on a phone: Gradle connected tests uninstall the app afterward.

[SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) is the living implemented design, maintained with architecture/security/workflow changes under [AGENTS.md](AGENTS.md). [TESTING.md](TESTING.md) contains phone steps; [VERIFICATION.txt](VERIFICATION.txt) records actual evidence and remaining checks.
