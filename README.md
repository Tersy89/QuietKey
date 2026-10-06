# Enigma — private text and file messages

Offline Android encryption for a selected member of an owner-managed group. Android 8.0/API 26+. No account or delivery backend. USB or a local hotspot carries joining packets; Android Internet permission is used for local sockets. The repository and APK distribution remain private.

## Download 0.4.5

**[Download Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk)**. Sign in, open the file and choose **Download raw**. Install it on both devices. It appears as **Enigma (test)**. This is an unaudited development prototype with no recovery; use disposable data while testing.

The app ID and debug signer are unchanged. Install over the existing test app if you want to retain its data. Older development formats are not a compatibility commitment. Update both devices to **0.4.5** for simpler hotspot joining and automatic received-message group selection. QKD3 text and file-v3 remain the current message formats.

## Protect and set up your device

1. Choose and repeat an app passphrase of 12–128 characters. Use **Show passphrase** to check typing; it starts hidden each time. Several random words are preferable. This cryptographically protects the local vault, including group secrets, groups and history. There is no forgotten-passphrase recovery.
2. Name the device and complete live camera setup: at least five seconds and 60 frames. Android Keystore generates the private RSA identity; camera input contributes additional input to group-secret generation. No video or audio is saved. The initial **Starter group** contains only this device.
3. **Device → Security & unlock** provides **Lock Enigma now**, passphrase change, and optional fingerprint unlock on supported Android 8+ devices with strong enrolled biometrics. Biometrics are an alternate unlocking route, not an additional required factor. Changing the passphrase disables biometric unlock. If an existing biometric setup fails after this update, unlock with your passphrase, then disable and enable biometric unlock again.

The protected vault is the app's local storage, not a separate personal file-storage feature. Leaving the app locks it; screen-off always locks. Camera setup and system file pickers have a controlled allowance of at most two minutes; the QR scanner allows at most five minutes to collect a large transfer. USB access prompts allow a bounded five-minute handoff; hotspot settings allow two minutes. Active nearby transfers close when the app locks, closes or leaves the foreground outside those handoffs. Screen-off still locks immediately. Fresh passphrase entry is required before readable-file export, reset and security-setting changes.

## Join with USB or personal hotspot

Only the owner creates the group. On that phone, open **Groups → group → Invite new recipient**. On the other phone choose **Groups → Join group**. Select **Personal hotspot (recommended)** on both. USB cable and QR fallback are also available.

### USB cable

1. Open USB joining on both phones, then connect a USB data cable.
2. Let one phone control USB and allow Android's USB/accessory access prompts on both. Either phone can be the USB host; that is separate from group ownership. USB debugging and a computer are not required.
3. The owner checks the connected person's device name/fingerprint and taps **Invite**.
4. The recipient checks the group/owner and taps **Join group**. The group details, unique group secret and complete public-key directory transfer together. A signed confirmation returns automatically; both screens show success.

Charging-only cables cannot carry the transfer. Some phone/cable combinations need an OTG adapter or do not support Android accessory mode. If USB cannot connect, use Personal hotspot. Physical S23/P30 accessory-mode interoperability still needs verification; emulator tests cannot establish it.

### Personal hotspot

1. In Android Settings, turn on the owner's personal hotspot. Connect the recipient's phone to it using the hotspot password. Stay connected even if Android says **No Internet**. The same private Wi-Fi network also works when it permits devices to communicate.
2. Select **Personal hotspot (recommended)** on both phones. The owner's invitation starts automatically and shows **Ready** with one small connection card. Returning from **Open connection settings** reopens joining automatically while the bounded unlocked handoff remains valid.
3. Recipient: tap **Scan owner's connection card**. One scan fills the address and temporary code and connects automatically; there are no frame/Next/Swap steps for this card. If the camera does not cooperate, **Copy connection card** on A and **Paste connection card** on B through your chosen transfer method. Manual address/code entry also remains available; the Wi-Fi gateway fills the address when available.
4. The owner checks the person/device and taps **Invite**. The recipient checks the owner/group and taps **Join group**. Keys and group details import together; the signed confirmation returns automatically. **Done** opens Home. In a two-person group the other person is selected automatically; check the displayed recipient before sending.

The app chooses the first available hotspot/private network address, preferring hotspot interfaces. If it chooses an address B cannot reach, use **Use another local address** on A to restart with the correct interface and a fresh code. Android still controls hotspot creation and Wi-Fi association; the app does not silently enable tethering or join a network. The compact card uses the existing scanner; physical S23/P30 scan reliability still needs testing.

The random connection code protects the local transfer and lasts at most five minutes. The card contains this temporary code and a private address, never a group secret. Copying explicitly puts the temporary code in your clipboard; share it only with the intended person. It is separate from the hotspot password and the app passphrase. No account, mobile data, Internet service, network discovery or separate key-trusting step is needed. Keep both apps open; Cancel/background/screen-off stops joining. Cancel and reopen the method to retry with a fresh request/code. A committed group remains saved if confirmation is interrupted; the first verified message also confirms membership. The owner can remove an unwanted pending member and readmit a fresh request without resetting either app.

### QR fallback

QR work is deferred. The existing manual scanner remains under **QR fallback**: B shows its request; A scans each code with **Code scanned → Next code on B → Scan next code on A** and approves. B taps **Swap phones**, scans A's invitation the same way, then taps **Join group**. **More** contains Copy/Paste and Start again. A can reopen a saved grant through **Invite new recipient → Continue QR invitation: [name]** or **Scan QR confirmation**. The initial standard ZXing scanner and explicit CameraX alternate remain unchanged; S23 focus/detection is unresolved. No new physical-camera fix is claimed in this version.

Every method transfers the same signed public request and invitation encrypted only for the recipient. Private identity keys never leave the phone. Pair with the known person's device: names/self-signed keys and possession of a hotspot code alone do not establish real-world identity. Invitations expire within five minutes even when messages use No expiry.

The owner repeats this for each new device, up to 30 per group. Existing members receive owner-signed directory updates with new messages/files. Offline devices update when a packet reaches them. A device identity can join multiple groups, each with an independent secret.

## Four tabs and sending

- **Home:** select a group and check the recipient, then write a message or send a file/image. A group with exactly one other member selects that person automatically. Groups with several other members require a choice; sending stays disabled until one is chosen.
- **Groups:** create/join and choose a group. Its details contain invitations, member management, renaming and removal.
- **Messages:** prepared and received text/files together across groups. Explicitly open an entry to decrypt it. Prepared means encrypted locally, not delivered or read.
- **Device:** device name, app version, updated how-to guide, fingerprint, **Security & unlock**, and full reset.

Text and arbitrary files, including images/PDFs, are encrypted before Share/Copy/Save is offered. Choose the corresponding person in the external messenger. The sender can reopen their own copy. Missing, self or unregistered recipients are rejected by both UI and protocol.

New packets combine a fresh secret wrapped to the sender/recipient public keys with the group's secret to derive their content key. Reception needs both the endpoint private key and the correct group secret. Another member's private key plus the shared group secret cannot open A↔B private content. Signatures authenticate the sender; encryption provides confidentiality.

History keeps the latest 200 encrypted entries and up to 150 MiB of encrypted file packages. Text/history metadata and group secrets are inside the encrypted vault. File packages remain encrypted in private storage. Plaintext message/file bodies are not persisted in history. Original and restored files remain at the locations you choose.

## TTL and receiving

Choose **Message expiry (TTL)** before encryption: **No expiry** by default, five minutes, one hour, 24 hours, seven days or 30 days. It starts at encryption, is signed/authenticated, and is capped by group expiry. Enigma checks expiry on opening, history reopening and readable-file export. Group expiry is a separate ten-year policy.

TTL uses device clocks. It cannot erase saved plaintext or prevent a modified recipient from bypassing expiry. It is not remote self-destruction.

In your messenger, **Share → Enigma (test)** opens received encrypted text after unlocking. Alternatively use **Messages → Open received text → Paste and open**, or type/paste then **Open pasted message**. Receiving has no group/recipient selectors: the packet identifies its group automatically, including when Starter group was previously selected. Line breaks/spaces added during copy/paste are removed; incomplete packets still fail. The unverified group hint only routes the packet; signature, recipient, group secret, expiry and replay checks still run before saving/displaying content. External repeated opening is rejected after its ID is durably saved; explicitly reopening authenticated local history remains possible before expiry.

Open received `.enigma` files through Messages or share an encrypted binary attachment to Enigma. The file header finds its group automatically. The app verifies the complete signature and every chunk before offering **Save restored file**, which requires the app passphrase again. Files up to 50 MiB are supported; names and MIME types are encrypted. Archives may be explicitly restored again before expiry and are marked previously opened. Saving readable content places it outside Enigma's protection.

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
