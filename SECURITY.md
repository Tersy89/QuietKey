# Security design and limits

This is an unaudited prototype with a custom message/pairing protocol. Obtain an independent cryptographic review and real-device testing before relying on it for sensitive communication.

## Ownership and groups

Every group stores the SHA-256 fingerprint of its creator's installation identity. `Protocol.invite()` checks that the sending public key matches this stored creator. Invitations are signed with that installation's Android Keystore private key. The receiver verifies the signature, unwraps a seed encrypted for its own identity, and confirms the creator's full fingerprint in person. Imported invitations always identify their signer as creator; physical fingerprint verification is essential. QR scanning alone does not authenticate a person.

All participants necessarily have the shared secret to decrypt messages. **Owner-only sharing is an official-client policy, not a cryptographic guarantee against a malicious participant.** A rooted/modified device, instrumentation, or a rewritten client can export the seed, plaintext, or manufacture a new invitation claiming another creator. Non-exportable local storage keys protect stored files; they cannot make a shared secret unknowable to a device that uses it. The app does not export seed QR codes or offer a member invitation button.

A device identity denotes an installation, not a human. This version uses device-level Android security but has no additional biometric/app PIN gate. Anyone using an unlocked device can act through its app. Changing this to a biometric gate is a future hardening step. Keystore hardware backing depends on the device and is not guaranteed.

## Ten-year key supply

A ten-year expiry is a local policy checked against the device's clock, not a guarantee of ten years of confidentiality. No finite video has a measurable "ten years worth" of entropy. The app uses system cryptographic randomness and a camera frame digest to make a seed, then derives keys as messages are sent. This provides computational security, not a one-time pad. Clock tampering can bypass expiry in a modified environment.

Seed creation:

- SHA-256 of live YUV camera planes and frame timestamps, at least 60 frames over five seconds.
- 32 bytes from a newly initialized system `SecureRandom`; the camera is never the sole entropy source.
- HKDF-SHA256(input = OS random bytes, salt = camera digest, info = UTF-8 `QuietKey/group-seed/v1`), producing 32 bytes.
- Group identity is a UUID; each group has a separate seed.

## Invitation wire format

`QKG1:` + unpadded Base64URL of a bounded binary envelope. Integers/longs are big-endian; strings use Java DataOutputStream modified UTF-8 (`writeUTF`), with a two-byte length prefix. Blobs have a four-byte length prefix. Body contains `invite-v1`, group UUID, name, expiry milliseconds, recipient SHA-256 identity fingerprint, creator X.509 public key bytes, and the encrypted 32-byte seed. Envelope contains body and signature blobs. Strict size bounds and end-of-packet checks apply.

Wrapping: RSA 2048 OAEP, SHA-256 primary digest, MGF1 SHA-1, empty label. The MGF1 choice is explicit for Android Keystore compatibility and is not a password hash. Signing: SHA256withRSA (PKCS#1 v1.5). The OAEP ciphertext does not itself bind the fields; the creator's signature covers the entire body. Recipient identity and signer are checked before decrypting. Signatures are verified against an embedded key at invitation time and require in-person fingerprint verification.

Public identity QR: `QKI1:` + Base64URL(X.509 RSA public key). The full SHA-256 fingerprint is shown for physical verification.

## Message wire format

`QKM1:` + Base64URL(envelope). The envelope contains body and RSA signature blobs. The body contains a header blob and a ciphertext blob. Header contains `message-v1`, group UUID, sender fingerprint, 16 random message-ID bytes, and 12 random nonce bytes.

AES key: HKDF-SHA256(input = group seed, salt = random message ID, info = SHA-256(header)), first/only 32-byte expansion block. AES-256-GCM uses the random nonce, 128-bit tag, and exact header bytes as AAD. The signature covers the exact body, including header and ciphertext. Public keys are looked up in the group's trusted identity map; plaintext is displayed only after signature and GCM checks and durable replay-log update.

Maximum plaintext is 16,000 UTF-8 bytes. Replay identifiers are stored per group and not pruned. Clearing app data, deleting/reimporting a group, or rolling back app data resets that defense. Delayed unseen messages are accepted; there is no authenticated timestamp or ordering guarantee. The app has no ratchet, forward secrecy, post-compromise recovery, or traffic-padding protocol. Compromise of a seed exposes captured messages for that group. Seed compromise alone does not forge another member's RSA signature.

## Local protection and known limits

- Android Keystore holds separate RSA identity and AES-256 vault-encryption keys, without export APIs.
- The vault is AES-GCM encrypted with a Keystore-generated nonce and AAD `QuietKey-vault-v1`. Atomic writes include creator identity, group seed, members, and replay log. Writes are synced and the committed ciphertext is verified before success. A missing key or corrupt vault fails closed. The encrypted vault has a 24 MB limit in addition to the per-group replay limit; exceeding capacity fails closed until groups are removed or a new installation is used.
- No network or microphone permission. Camera frames remain in memory; screenshot protection applies to app and QR scanner windows. It cannot prevent a second camera photographing the screen.
- Strings, plaintext, and shared seeds exist in application RAM while used. Java cannot guarantee zeroization of every copied buffer/string. A compromised OS or keyboard can observe them. Flags requesting no keyboard learning are advisory.
- Metadata (group identifier, sender fingerprint, length, and pairing metadata) remains visible in packets. The carrier app knows recipients and timing. The app encrypts pasted text, not attachments, notification text, or the transport's own metadata.
- There is no key recovery, owner transfer, automatic revocation, contact synchronization, or member removal protocol. Lost creator keys require creating a new group. Removing a member requires a new seed and fresh owner-issued invitations for remaining members; old members can still read old-group ciphertext.
- A modified device can bypass the official client's ownership and expiry checks. There is no claim of malicious-recipient key containment.

## Primary references used

- Android cryptography recommendations and explicit OAEP/MGF1 behavior: https://developer.android.com/privacy-and-security/cryptography
- Android Keystore model: https://developer.android.com/privacy-and-security/keystore
- Camera2 YUV ImageReader API: https://developer.android.com/reference/android/hardware/camera2/package-summary
- Android Gradle Plugin 8.7 compatibility: https://developer.android.com/build/releases/agp-8-7-0-release-notes
- ZXing scanner source/documentation: https://github.com/journeyapps/zxing-android-embedded
- HKDF test vectors: RFC 5869, test case 1.
