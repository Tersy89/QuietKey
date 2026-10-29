# Enigma 0.6.0 security model

> **0.7.0 (branch feature/0.7.0-messages):** identities are now Ed25519/X25519 keys in the passphrase vault, not RSA in the Keystore. Messages use X3DH one-time keys, giving per-message forward secrecy except for "reduced protection" messages. Readable history is stored encrypted under the vault master key. Statements below about RSA, QKD3/file-v3 or ciphertext-only history describe 0.6.0. Since 30 October every key agreement is hybrid (X25519 + ML-KEM-768) and every packet signature is hybrid (Ed25519 + ML-DSA-65), so one broken algorithm family is not enough to read or forge a message. Unlocking takes the passphrase or biometrics and then a 4-digit PIN; five wrong PINs or the panic PIN (default 9999) erase the app. The wrong-PIN count can be rolled back on a rooted phone, though every try still needs the first step. Statements below that call biometrics an alternative to the passphrase, or say failed attempts never wipe, describe 0.6.0. See SYSTEM_DESIGN.md "0.7.0 message security" for the current model and its limits. Nothing here is a security audit.


Development prototype, not independently audited. See SYSTEM_DESIGN.md for implemented architecture/formats and VERIFICATION.txt for executed evidence. SECURITY_REVIEW.md records the historical 0.3 findings; new 0.4 formats correct the independent group-secret requirement.

## Intended protection

Each installation has a non-exportable Android Keystore RSA identity. Each group has an independent random 32-byte secret. Device identities can participate in several groups. New text/files are encrypted for one selected other member, with a separate wrapped slot for the sender's own copy. Missing, self or unknown recipients are refused before output.

Compromise of C reveals C's usable private key and group secrets available to C. Those must not reveal captured A↔B private packets or forge A's signatures, assuming A/B and their authenticated key bindings remain uncompromised. Signatures provide authenticity; recipient encryption provides confidentiality. A trusted owner authenticates member introductions but is not automatically a recipient of every message. A malicious owner can introduce deceptive names/devices: verify the known owner during initial pairing and retain visible fingerprints.

No protection is promised for plaintext on a compromised unlocked endpoint, malicious recipients forwarding plaintext, or compromised OS/keyboards. Static endpoint keys provide no forward secrecy: later compromise of A/B's usable key plus the group secret can reveal captured packets. There is no ratchet, post-compromise recovery, owner transfer or key recovery/export.

## Algorithms and bounded formats

- RSA-2048 OAEP wrapping: SHA-256, MGF1 SHA-1, empty label, explicitly selected for Android Keystore interoperability. RSA signatures: SHA256withRSA/PKCS#1 v1.5, 256 bytes. The same identity currently signs and decrypts.
- AES-256-GCM: fresh nonces, 128-bit tags. HKDF-SHA256 for key derivation. OS SecureRandom is the foundation of generated secrets; camera data is additional input, not a measurable entropy guarantee.
- Big-endian numbers, Java modified UTF-8 strings, length-prefixed bounded blobs, strict versions/trailing-data checks. Public fingerprints are SHA-256 of X.509 RSA public-key encoding.
- New QKD3/direct-v3 and ENF1/file-v3 wrap a fresh random endpoint secret R to sender/recipient. Final AES key = HKDF(R, salt group secret, info versioned purpose + group ID + SHA-256(complete header)). Unwrapping R alone is insufficient. Header is GCM AAD, and the exact packet is RSA-signed.
- Text limit 16,000 UTF-8 bytes; wire 75,000 characters/body 40,000/header 20,000 bytes to accommodate the encrypted signed directory. Empty messages are refused.
- Files: 50 MiB, 65,536-byte chunks, encrypted filename/MIME metadata. Nonce = random eight-byte base + four-byte sequence index. Chunk AAD binds header/index/kind. Strict sequence, metadata/data/end framing and whole-package signature are verified before readable export. No plaintext cache file is created.

Existing QKD1/QKD2/file-v1/file-v2 readers remain but their endpoint keys can decrypt without an independent group-secret derivation. QKM1 is legacy shared-group encryption: every holder of its group secret can decrypt. These older packets do not gain new protection retroactively. The current UI creates only new recipient-private formats; compatibility is not a development release commitment.

## Owner-managed joining and trust

B creates a signed QKJ1 request containing public key, name, random 16-byte request ID and five-minute lifetime. A receives the request over the KEK-protected nearby Wi-Fi link or as a manually transferred file, and explicitly confirms admission. A signs an updated member directory, then AES-encrypts the complete group details/secret/directory; its fresh AES key is RSA-wrapped only for B. QKG2's signed/GCM-authenticated header binds owner public key, B fingerprint, active request ID, expiry, wrapped key and nonce.

B verifies recipient, active request, lifetime, owner signature, GCM and signed directory before committing trust. All member public keys import together; no separate public-key scan is required. Used joining IDs are retained across local group deletion. Interrupted owner invitations are cached inside the encrypted vault (maximum 64). Reissuing a used request is permitted only for its exact saved request, group, member and still-pending owner grant; the recipient must remain present and unblocked. An unchanged directory reuses the original packet; changed directories regenerate the recipient-bound packet after owner confirmation. Removed/cancelled pending grants cannot reuse that request. Member/group removal clears corresponding cached/pending state, while used-request tombstones remain. A fresh signed request is required after removal or for an older build's uncached used request. Start again replaces the joining device's active request, invalidating earlier invitations for that session. Reimport refuses existing groups rather than erasing history. QKA1 acknowledgement (automatic over nearby Wi-Fi; an optional file with manual transfer) needs B's signing key and an already-approved pending request; it cannot add members.

The first owner identity is authenticated by the direct physical pairing context. Nearby joining pins the owner fingerprint carried in the scanned or tapped QKB1 code; manual transfer relies on comparing a six-digit check code. Neither establishes a person's real-world identity or prevents someone presenting an entirely different owner's code during initial pairing. A photograph of B's request is not proof of live presence, but the invitation can be decrypted only using B's private key and accepted only in that active request session. An observer photographing A's invitation cannot join using C's key. An authorized or compromised member can redistribute their group secret; software cannot stop this.

Owner-signed directories pin group/owner/expiry, distinct member keys/names and increasing revisions. Updates verify against the pinned owner key; old revisions do not roll back current trust, and conflicting equal revisions fail. Local blocked identities remain blocked unless the owner explicitly reinvites that device. Encrypted directory carriers in new messages/files automatically introduce approved members after packet authentication. Devices receive changes only when a packet reaches them; there is no network synchronization or immediate group-wide revocation.

### Layering: joining is separate from message encryption

Joining and message encryption are independent layers. Joining only delivers the group's 32-byte secret, its owner-signed member directory and group settings to one approved device. Messages/files then use their own per-packet keys (a fresh endpoint secret RSA-wrapped to sender/recipient, combined with the group secret through HKDF). No joining key is ever used for message content, and no message key is used for joining.

During nearby joining the group secret is protected by three independent layers, outermost first:

1. **Wi-Fi link:** WPA2/WPA3 of the temporary local-only hotspot (or the shared private Wi-Fi). This layer is not relied on: anyone who sees the QR learns the hotspot password, and a spoofed access point is assumed possible.
2. **One-time transport key (KEK):** 32 random bytes generated by the owner for each invitation and carried only in the single QR/NFC tap. HKDF-SHA256 derives the EJW1 frame key; every frame is AES-256-GCM with a fresh nonce and authenticated header. A spoofed hotspot or on-path device without the KEK cannot read or alter frames.
3. **Recipient encryption (QKG2):** the group secret, settings and directory are AES-256-GCM encrypted under a fresh key that is RSA-OAEP-wrapped only to the recipient's non-exportable Android Keystore key, and the envelope is signed by the owner. Someone who photographed the QR and knows the KEK still cannot decrypt the invitation, and cannot forge one because the recipient refuses any invitation whose owner differs from the fingerprint in the scanned code.

Manual file transfer omits layers 1–2 and relies on layer 3 plus the check code; those files can therefore travel over any medium.

### Nearby joining: single QR or NFC tap, then Wi-Fi

The owner's app starts an Android local-only hotspot (no Internet, randomly generated network name/password, closed when joining ends) or, if chosen, uses the current private Wi-Fi. It binds a TCP listener on that interface's RFC1918 address only (port 45873, or an ephemeral port if busy) and builds QKB1: mode, hotspot SSID/passphrase/security type, owner IPv4/port, the 32-byte KEK, the owner's SHA-256 identity fingerprint and a five-minute expiry (about 250 characters). QKB1 is shown as one QR and served by an NFC host card-emulation service. The service answers only Enigma's proprietary AID, requires an unlocked device, and returns "not found" whenever no invitation screen is open; the payload is cleared when the first verified request arrives or the screen closes.

The recipient scans the QR (ZXing, FLAG_SECURE capture activity) or reads it in NFC reader mode, verifies version/fields/expiry, and connects through Android's Wi-Fi network-request API (one system consent prompt; app-scoped, no Internet, released on close). Its socket is bound to that network. Only literal private IPv4 endpoints are used; no discovery, DNS or backend request is made.

Both screens show a six-digit check code: HKDF(KEK, SHA-256(exact signed QKJ1 request), "check-code"). The owner invites only if the codes match. Someone who photographed the QR and connected first would produce a code that does not match the real recipient's screen (one-in-a-million chance), because the real recipient's fresh request is not known in advance. The owner accepts one verified request per session and at most five failed initial connections; reads before approval time out after ten seconds.

The recipient additionally requires the invitation's owner identity to equal the fingerprint in QKB1, so a different owner or spoofed hotspot cannot substitute its own group. No group is committed until request/expiry/signature/GCM/directory verification and the recipient's confirmation succeed. Owner pending membership is saved when the owner approves; a declined or interrupted recipient may remain Invited until removed or retried.

Android requires Location permission with Location enabled (Android 8–12) or Nearby Wi-Fi devices permission flagged neverForLocation (13+) to start the local-only hotspot. Enigma requests it only when inviting and never reads location or scan results. The owner phone may leave its normal Wi-Fi while the hotspot runs. Recipients on Android 8–9 are shown the hotspot name/password to connect manually. IP addresses, timing and sizes remain visible on the local network.

EJW1 framing has magic, mode, packet type, strict per-direction sequence and bounded length (at most 30,028 body bytes). Only request → invitation → signed receipt → done is valid; downgrade, reflected/wrong-type frames, malformed text, oversized input, truncation and repeated sequence fail. Listeners, sockets, hotspot reservation, network request, NFC payload and pending approvals close on Cancel, lock, Activity destruction and the five-minute deadline. Group material awaiting recipient confirmation is cleared when cancelled. QR scanning has a bounded five-minute handoff and the Wi-Fi consent prompt 90 seconds; screen-off always locks. Physical vendor behaviour (hotspot start, network-request prompt, NFC HCE on the owner's phones) remains a manual check, not emulator evidence or an independent audit.

### Manual file transfer

For USB cables or any file copy: the recipient saves its signed QKJ1 request as a text file; the owner opens it, approves and saves the recipient-encrypted, owner-signed QKG2 invitation; the recipient opens it and may save a signed QKA1 confirmation. Copy text is also offered. These files contain the same packets as nearby joining and are safe to carry over untrusted media, but the transport provides no authentication, so both screens show a six-digit check code, SHA-256("Enigma/manual-check/v1" ‖ request ‖ owner public key). Compare it in person or on a call before joining. A substituted request or invitation changes the code on one side. Request files expire five minutes after creation.

### Removed in 0.5.0

USB Open Accessory direct transfer, the typed hotspot connection code and multi-frame QR joining (QKF1 frames, CameraX scanner) were removed, together with the USB host/accessory features and CameraX dependencies.

## Password, biometric and stored-data protection

The app requires a 12–128-character passphrase. Argon2id v1.3 derives a 32-byte key with a 16-byte random salt, 64 MiB memory, three iterations and four lanes (Bouncy Castle 1.86 low-level implementation; no global provider replacement). Fixed parameters/bounded envelopes prevent attacker-controlled memory inflation. AES-GCM wraps a random 32-byte vault master key using that derived key; a non-exportable Keystore AES key encrypts the whole envelope again in password.qkl.

Vault format 2 uses the master key, fresh 12-byte nonce and AAD Enigma-vault-v2. groups.qkv stores group seeds, signed directories, aliases/blocks, joining/replay state, encrypted text and history metadata. Atomic synced/verified commits fail closed on corruption/missing keys. The vault ciphertext limit is 24 MiB. This is cryptographic encryption, not just a password UI check.

Optional biometric unlock uses AndroidX Biometric on API26+ with supported strong enrolled biometrics and is an explicitly enabled alternative. A separate per-operation authentication-bound Keystore AES key wraps the same master key. AndroidX BiometricPrompt/CryptoObject requests BIOMETRIC_STRONG and must authorize the cryptographic operation. GCM AAD and finalization occur only after successful authentication; cancellation cannot open the vault. Unsupported hardware or invalidated keys retain the passphrase route. Changed enrollment invalidates this route where supported. Password change disables it. Fresh password entry is still required for readable-file export, reset, password change and biometric configuration.

The RSA identity and device password-envelope key are not themselves configured for per-operation user authentication. Password protection encrypts the group secrets/local vault; it does not physically bind every RSA operation to the password. A compromised executing app can potentially invoke those Keystore keys. Hardware-backed storage is device-dependent. Password/bio authentication cannot make a compromised unlocked endpoint safe.

Unlock and fresh-auth wrong attempts have a persistent increasing UI delay capped at 60 seconds. It does not prevent offline guessing or a rooted/modified client bypassing delays. Strong passphrases and Argon2 address guessing cost. No password recovery or automatic wiping is provided.

## Session, history, expiry and exports

Leaving the app locks the session, clears UI and queues disposal of loaded secrets; screen-off always locks. Camera setup/document-picker handoffs have a maximum two-minute allowance; scanning the joining code allows up to five minutes, bounded by the joining lifetime, and the Wi-Fi connection prompt 90 seconds; locking cancels nearby transport and pending approvals. Session generation checks reject stale asynchronous results; readable export checks the session while writing. Missing/locked state refuses protected actions. FLAG_SECURE covers app/scanner/nearby windows; external cameras remain possible.

Java strings/keys/plaintext exist in RAM while unlocked. Byte arrays are cleared where practical; full zeroization of every copy is not guaranteed. Keyboard suggestions/learning and autofill are disabled by request, not a defense against a malicious keyboard.

History holds 200 encrypted entries and 150 MiB of encrypted packages with opaque UUID names. Eviction follows a successful vault commit. FileProvider exposes only ciphertext cache packages and is nonexported. Cache ciphertext is cleaned after 24 hours at next launch. Backups/device-transfer backups are disabled. Internet and network-state permissions support local hotspot sockets/gateway lookup. There is no microphone or broad-storage permission; sharing delegates ciphertext to a user-selected external app. Enigma is not a VPN.

Text replay IDs are committed before display, bounded at 100,000 per group without pruning. Explicit local history reopening can reuse that authenticated entry's ID while rechecking signature/trust/expiry. File archives can be explicitly restored repeatedly and are marked previously opened. Reimport/rollback/reset can reset replay protection; there is no ordering guarantee.

TTL defaults to No expiry; options are five minutes, one hour, 24 hours, seven days and 30 days, clamped to separate group expiry. Signed/GCM timestamps are checked on text opening/history reopening/file inspection and again before readable export. now >= expiry refuses access; timestamps more than five minutes ahead are rejected. TTL uses local clocks, cannot recall saved plaintext, and can be bypassed by modified recipients with retained secrets.

In-app viewing (0.6.0): after full package verification and a durable replay-ID commit, images up to 25 MiB and text up to 1 MiB are restored into a pre-sized in-memory buffer that is zeroed after decoding; the resulting Bitmap/String stays in the FLAG_SECURE window only while the viewer is open and is released on navigation, expiry or lock. Nothing readable is written to app storage or cache. Image decoding uses Android's BitmapFactory on authenticated content from a verified group member; a malicious member could still target decoder bugs, as with any image viewer. Viewing does not ask for the passphrase again (the session is already unlocked, as for text); writing a readable copy does.

Opening from other apps (0.6.0): MainActivity accepts ACTION_VIEW and ACTION_SEND for application/octet-stream, *.enigma content URIs and text/plain. Only content:// URIs from other apps are read (file:// and Enigma's own FileProvider are ignored), and only after unlock; the content goes through the same bounded copy and full verification as a picked file, so a hostile app can at most cause an error.

NFC joining (0.6.0): the recipient re-arms reader mode on every resume while waiting, and the owner disables its own NFC polling on Android 15+ while serving the code, so neither phone opens Android's tag screen (which would background and lock Enigma). This changes reliability only; QKB1 contents and protection are unchanged.

File restoration to a chosen location streams directly to the provider after verification and fresh authentication. Failure requests deletion of the partial destination; the provider may retain copies or fail deletion. Selecting cloud storage can upload readable content. Original, shared and restored files outside the app are not encrypted/deleted by Enigma. Visible packet metadata includes group ID, sender/recipient fingerprints, timing fields and approximate size; the external carrier knows delivery contacts/timing. No padding or transport-metadata protection.

## Removal and reset

Local trust removal stops using that identity here; it does not erase the group secret or private keys on another device. Replace the group and invite only remaining members for revocation. Automatic authenticated rekeying is not implemented. Group deletion removes its local history/packages and secret, but other copies remain.

Reset requires fresh passphrase and exact typed RESET, then invokes Android clearApplicationUserData. It destroys this installation's keys, vault/history/cache, setup, permissions and URI grants and terminates the app while retaining the APK. External files remain. Android Settings offers the destructive reset route after forgotten passwords. Neither reset nor deletion guarantees physical flash erasure. There is no recovery.

## Primary references

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) and [cryptography/OAEP](https://developer.android.com/privacy-and-security/cryptography)
- [Cryptographic biometric authentication](https://developer.android.com/identity/sign-in/biometric-auth)
- [Argon2 and test vectors, RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html)
- [HKDF, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869)
- [Android document storage](https://developer.android.com/training/data-storage/shared/documents-files)
- [Local authentication bound to cryptography, OWASP](https://mas.owasp.org/MASWE/MASVS-AUTH/MASWE-0020/)

- [Android local-only hotspot](https://developer.android.com/reference/android/net/wifi/WifiManager), [Wi-Fi network request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap), [Host card emulation](https://developer.android.com/develop/connectivity/nfc/hce), [USB accessory](https://developer.android.com/develop/connectivity/usb/accessory) and [AOA protocol](https://source.android.com/docs/core/interaction/accessories/aoa)
- [Local network permissions](https://developer.android.com/privacy-and-security/local-network-permission)
