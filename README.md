# Enigma — private text and file messages

Offline Android encryption for a selected member of an owner-managed group. Android 8.0/API 26+. No account or delivery backend. A temporary local hotspot (started by the app) or a manually copied file carries joining packets; Android Internet permission is used for local sockets only. The repository and APK distribution remain private.

## Download 0.5.0

**[Download Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk)**. Sign in, open the file and choose **Download raw**. Install it on both devices. It appears as **Enigma (test)**. This is an unaudited development prototype with no recovery; use disposable data while testing.

The app ID and debug signer are unchanged. Install over the existing test app if you want to retain its data. Older development formats are not a compatibility commitment. Update both pairing devices to **0.5.0**: joining now uses one QR code or an NFC tap, or manual file transfer. USB direct transfer and multi-frame QR joining were removed. QKD3 text and file-v3 remain the current message formats.

## Protect and set up your device

1. Choose and repeat an app passphrase of 12–128 characters. Use **Show passphrase** to check typing; it starts hidden each time. Several random words are preferable. This cryptographically protects the local vault, including group secrets, groups and history. There is no forgotten-passphrase recovery.
2. Name the device and complete live camera setup: at least five seconds and 60 frames. Android Keystore generates the private RSA identity; camera input contributes additional input to group-secret generation. No video or audio is saved. The initial **Starter group** contains only this device.
3. **Device → Security & unlock** provides **Lock Enigma now**, passphrase change, and optional fingerprint unlock on supported Android 8+ devices with strong enrolled biometrics. Biometrics are an alternate unlocking route, not an additional required factor. Changing the passphrase disables biometric unlock. If an existing biometric setup fails after this update, unlock with your passphrase, then disable and enable biometric unlock again.

The protected vault is the app's local storage, not a separate personal file-storage feature. Leaving the app locks it; screen-off always locks. Camera setup and system file pickers have a controlled allowance of at most two minutes; scanning the owner's joining code allows at most five minutes and Android's Wi-Fi connection prompt 90 seconds. Active nearby transfers close when the app locks, closes or leaves the foreground outside those handoffs. Screen-off still locks immediately. Fresh passphrase entry is required before readable-file export, reset and security-setting changes.

## Join a group

Only the owner creates the group. On that phone open **Groups → group → Invite new recipient**. On the other phone choose **Groups → Join group**. Joining copies the group's secret, settings and member public keys to the new phone, encrypted so only that phone can open them. It is separate from message encryption.

### Nearby: one QR code or an NFC tap (recommended)

1. Owner: choose **Nearby: QR code or NFC tap**. Allow the permission Android asks for the first time (Nearby devices on Android 13+, Location on older phones, with Location turned on). Enigma starts its own private hotspot with no Internet and shows one code. No trip to Settings is needed.
2. Recipient: choose **Scan or tap the owner's phone**, then tap **Scan owner's code**, or hold the phones back to back with NFC on. Tap **Connect** if Android asks to connect to the owner's device.
3. Both phones show a six-digit **check code**. Owner: tap **Codes match · Invite** only if both codes are the same. Recipient: check the code and tap **Join group**. A signed confirmation returns automatically and both screens show success.

The code works once and expires after five minutes. Only show it to the person joining. Keep both apps open; Cancel, background or screen-off stops joining. The owner's phone may drop its normal Wi-Fi while the joining hotspot runs and reconnects afterwards. If both phones are already on the same private Wi-Fi, the owner can choose **Nearby on the same Wi-Fi** instead.

### Manual transfer (USB cable or any file copy)

1. Recipient: **Join group → Manual transfer: save my request file**. Copy the file to the owner's phone, for example through a computer over USB cables, or use **Copy text**.
2. Owner: **Invite new recipient → Manual transfer: open their request file**, check the device and tap **Invite**, then **Save file** for the invitation and copy it back.
3. Recipient: **Join group → Manual transfer: open the owner's invitation file**. Compare the six-digit check code with the owner in person or on a call, then tap **Codes match · Join**. Optionally save a confirmation file for the owner (**Open their confirmation file**); the first verified message also confirms joining.

The files contain only a public request, an invitation encrypted for the recipient's phone, and a signed confirmation, so any transport is fine. Request files expire five minutes after they are made.

Private identity keys never leave the phone. Names and self-signed keys alone do not prove a real-world identity; pair with the known person's device and compare check codes.

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

Paste a complete received text packet; Enigma opens it in the group named inside the packet, whichever group is selected, and ignores line breaks a messenger may add. External repeated text opening is rejected after its ID is durably saved; explicitly reopening authenticated local history remains possible before expiry.

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
