# Enigma — private text and file messages

Offline Android encryption for a specific verified group member. Android 8.0/API 26+. No account, Enigma server or Internet permission. The repository remains private.

## Download and update

**[Download Enigma.apk from main](https://github.com/Tersy89/QuietKey/blob/main/Enigma.apk)**. Sign in, open the file and click **Download raw**. Install over the existing QuietKey/Enigma test app; do not uninstall or clear storage to update. The app ID, signing identity, vault format and Keystore aliases are retained.

Both people need **0.3 or newer** for new QKD2 text and file-v2 .enigma packages. Older QKM1/QKD1 text and file-v1 packages still open, with no retroactive TTL. Old versions did not retain a conversation history, so previously shared packets must be imported again to appear here. There is no key recovery. This is an unaudited prototype; see SECURITY.md.

## Four tabs

- **Home:** choose a group and explicitly choose another verified recipient, then Write a message or Send a file or image. Quick group buttons change the active group. No automatic recipient or My device fallback.
- **Messages:** all newly prepared and received text/file entries together, across groups. New text, attachments and received packets are accessible here. Open an entry explicitly to decrypt it. Prepared means encrypted locally; delivery/read receipts are not available from outside messengers.
- **Groups:** create, join, invite, rename and manage members, aliases, public fingerprints and local trust. Member lists are local, not a synchronized directory.
- **Device:** device name, setup status, public identity QR, app version/information, How to use Enigma, and Reset app & delete local data.

Messages retains the latest 200 encrypted entries, with up to 150 MiB of encrypted file packages; oldest entries are removed locally as needed. Text packets and metadata are in the Keystore-encrypted vault; file content is stored as recipient/sender-encrypted packages. Plaintext is not stored in conversation history. Original source files and restored files remain in the user's chosen locations.

## Set up and pair

1. Name your device and complete live camera setup (at least five seconds and 60 frames). Android Keystore generates the private RSA key; camera input contributes to a separate personal/group secret. No video or audio is saved. The initial Personal vault group contains only this device and cannot send until another verified member is added.
2. A creates a group in Groups. B shows their **Device** public identity QR.
3. A uses **Invite a new member**, scans B's QR and compares the entire fingerprint with B in person before confirming. A then shows the invitation QR.
4. B uses **Join with invitation QR** and verifies A's entire fingerprint in person before joining.
5. Home: select that group and B explicitly. Write text or choose a file. Enigma encrypts it before offering Share/Copy/Save encrypted file. Choose B in your messenger's delivery screen.

For more members the owner repeats invitations. Other members explicitly scan/verify each other's public QR before exchanging private packets. Trusting a QR stores a public key but does not issue an invitation. New outgoing text/files reject missing, self or unregistered recipients in the UI and protocol layer. Content uses a fresh AES key; that key is encrypted separately to the recipient's public key and the sender's public key. Another member's shared group seed cannot decrypt it.

## TTL and receiving

Before encryption choose **Message expiry (TTL)**: No expiry (default), 5 minutes, 1 hour, 24 hours, 7 days or 30 days. It starts at encryption, is authenticated inside the packet, and is capped by the group's expiry. Enigma checks it when opening text, opening an archive, reopening history and saving a restored file. Group expiry remains a separate ten-year local policy.

TTL uses device clocks. It cannot erase plaintext already saved or stop a recipient with a modified client or retained private key. Old packets carry no message TTL. Enigma does not claim remote self-destruction.

Use **Open received text** to paste a complete packet; choose its group and decrypt. External repeated text opening is rejected after the opened ID is saved durably. You can explicitly reopen the authenticated encrypted local history entry without accepting that external replay again.

Use **Receive an encrypted file** to open a .enigma package. Enigma verifies the sender, whole package signature and every chunk before Save restored file. File archives may be explicitly restored again before expiry and are marked previously opened. Files up to 50 MiB are supported; names and MIME types are encrypted, with names capped at 120 characters. Saving a restored file creates readable content outside the protected app vault.

## Reset and membership removal

**Device → Reset app & delete local data** requires typing **RESET**. Android clears the app's local data, keys, setup, groups, encrypted history/cache, permissions and URI grants; the app stays installed and closes. Launch it again to set up a new identity. Old encrypted content may become unreadable. Downloaded, shared, original and restored files outside the app remain elsewhere. Cancel preserves data.

Local member removal does not revoke keys already on another device. Replace the group and invite remaining members when revocation is needed. Removing a group here also removes its local message/file history; others retain their copies.

## Build and test

JDK 17, SDK Platform 35, Build Tools 34.0.0, included checksum-pinned Gradle 8.9 wrapper and AGP 8.7.3. This PC's tools/SDK paths are configured in ignored local files.

```powershell
.\tools\dev.ps1 build
.\tools\dev.ps1 protocol
.\tools\dev.ps1 devices
.\tools\dev.ps1 install -Serial YOUR_PHONE_SERIAL
.\tools\dev.ps1 device-tests -Serial YOUR_PHONE_SERIAL
```

Debug output: app/build/outputs/apk/debug/app-debug.apk, app ID app.quietkey.debug. Keep the same debug signing key for compatible updates. Gradle connected tests uninstall the app afterward; use the preserving device-tests helper on a retained phone installation.

See TESTING.md for phone steps and VERIFICATION.txt for executed evidence. Emulator tests cover camera, mandatory recipient selection, unified history, real system document-picker restoration, TTL, reset and Keystore behavior. Physical-phone QR pairing remains a manual check.
