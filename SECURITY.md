# Enigma security design and limits

This is an unaudited prototype with custom pairing, text and file protocols. It has no ratchet, forward secrecy or post-compromise recovery. A device identity denotes an installation, not a person. Public-key fingerprints must be compared in person; a scanned QR alone does not authenticate someone.

## Keys and camera setup

Android Keystore generates a non-exportable RSA-2048 identity and a separate AES-256 vault key. Existing QuietKey app IDs, aliases and vault AAD are retained for upgrades. Hardware backing depends on the device. There is no additional biometric/app PIN gate: someone using an unlocked device can act through the app.

Camera capture hashes live YUV planes and timestamps over at least 60 frames and five seconds measured from the first frame. No recording or audio is saved. Group seed creation uses HKDF-SHA256(input = 32 fresh OS SecureRandom bytes, salt = camera digest, info = UTF-8 `QuietKey/group-seed/v1`), yielding 32 bytes. Each group has a UUID, independent seed, creator fingerprint and ten-year expiry. Personal setup creates one such group for the device itself. Camera input does not generate the RSA private key or establish a measurable amount of entropy. Ten-year expiry is a local clock policy, not a ten-year confidentiality guarantee or one-time pad.

New text/file content keys use HKDF-SHA256(input = 32 fresh OS random bytes, salt = group seed, info = `Enigma/private-message-key/v1` or `Enigma/private-file-key/v1`). Each content key is wrapped separately to the selected recipient and sender using RSA-OAEP. Possession of the shared group seed alone does not decrypt these packets. Either designated private RSA key can open them, including captured old packets if that key is later compromised.

## Groups and trust

The official client allows new recipients only from the selected group's locally verified fingerprint/public-key map, checks sender membership and rejects packets for a different group or recipient. Fingerprint-to-key consistency is checked. Aliases and group renaming are local display data, not authenticated identity claims. Member lists are local; they are not an owner-signed, globally synchronized membership ledger. A member can explicitly trust another public identity locally. Outside messenger contacts are not cryptographically linked to the selected fingerprint.

Only the recorded creator can issue an invitation through `Protocol.invite()` and the UI. The signer must match the creator fingerprint; the invitation is encrypted for its recipient. A modified client can redistribute shared group secrets or bypass local membership/expiry rules. Owner-only invitations are an official-client policy against malicious participants. A recipient can always retain or forward plaintext they receive.

Removing local trust makes this device refuse further use of that identity. It cannot revoke keys held elsewhere, erase old packets, or update other devices' lists. Group-wide revocation requires a fresh group and fresh invitations to remaining members. Local group deletion removes the seed, members and history from the vault; rejoining resets that history. Losing the creator identity requires a new group. There is no owner transfer, key recovery or private-key export.

## Encoding and invitations

Integers and longs are big-endian. Strings use Java DataOutputStream modified UTF-8 with a two-byte length prefix. Blobs have a four-byte length prefix; bounded parsers reject invalid lengths and trailing data.

`QKI1:` + unpadded Base64URL(X.509 RSA public key) is a public identity. Its fingerprint is SHA-256 of the encoded key.

`QKG1:` + Base64URL(envelope) contains body and signature blobs. The body contains `invite-v1`, group UUID, name, expiry milliseconds, recipient fingerprint, creator X.509 key and RSA-wrapped 32-byte seed. The signature authenticates the exact body. Acceptance checks recipient/signature and requires explicit physical creator-fingerprint confirmation.

RSA wrapping is RSA-2048 OAEP with SHA-256 primary digest, MGF1 SHA-1 and empty label, explicitly selected for Android Keystore interoperability. Signatures use SHA256withRSA (PKCS#1 v1.5), 256 bytes. MGF1 SHA-1 is not a password hash.

## New private text: QKD1

`QKD1:` + Base64URL(envelope). Envelope = body and signature blobs. Body = header and ciphertext blobs. Header = `direct-v1`, group UUID, sender fingerprint, recipient fingerprint, 16 random message-ID bytes, 12 random nonce bytes, recipient-wrapped content key and sender-wrapped content key.

AES-256-GCM uses a 128-bit tag and exact header as AAD. The RSA signature covers the exact body. The receiver verifies trusted keys, signature, group, intended recipient/sender, expiry and replay policy, unwraps its content key and authenticates GCM. A replay identifier is saved durably before plaintext is displayed. Maximum plaintext is 16,000 UTF-8 bytes; empty messages are rejected. IDs have a 100,000-entry per-group history limit. Delayed unseen messages are accepted; there is no authenticated ordering or timestamp.

## Legacy shared text: QKM1

Existing `QKM1:` packets remain readable, explicitly labeled as legacy group messages. All holders of that group seed can decrypt them. Their header contains `message-v1`, group UUID, sender fingerprint, 16-byte ID and 12-byte nonce. The AES key is HKDF-SHA256(group seed, random ID, SHA-256(header)); header is GCM AAD, and body is RSA-signed. Compromise of a legacy group seed reveals captured QKM1 ciphertext. The updated UI only creates QKD1 text.

## Private files: ENF1 / .enigma

Binary package: four-byte magic `ENF1`, bounded header blob, authenticated chunks, fixed 256-byte RSA signature. Header = `file-v1`, group UUID, sender/recipient fingerprints, 16-byte random ID, 8-byte nonce base and content key wrapped separately to recipient and sender. Metadata is encrypted; original filename/type are not in the clear header.

Chunks use AES-256-GCM with nonce = 8-byte base || 4-byte sequence index. AAD = exact header || 4-byte index || 1-byte kind. Kind 0 is encrypted filename and MIME metadata; kind 1 is nonempty file data in chunks of at most 65,536 bytes; kind 2 is an authenticated empty end marker. The signature covers magic, header and all chunk framing/ciphertext. Strict order, length, total-size, chunk-count, final-marker and trailing-data bounds apply. Maximum source size is 50 MiB. Filenames are stripped of paths/control characters and capped at 120 characters; invalid MIME types become application/octet-stream.

Inspection authenticates all chunks and the complete signature before the UI offers restoration. The app copies incoming ciphertext into private cache; it creates no plaintext cache file. On explicit Save restored file, it saves the opened ID before streaming readable data to the chosen document provider. Failed exports request deletion of the partial destination; a provider may retain its own copies or fail deletion. Providers may upload saved documents if the user selects cloud storage.

Unlike text, encrypted file archives can be explicitly restored multiple times. Previously opened files are marked in the UI and still require full verification and Save. File-open history shares the bounded per-group history with text. It is not a promise to reject duplicate files. External saved readable files and original sources are not encrypted or deleted by Enigma.

## Local protection and remaining limits

- AES-GCM vault AAD is `QuietKey-vault-v1`; it stores seeds, owner identities, trusted keys, aliases, setup state and history. Writes are atomic, synced and verified before success. Missing keys/corruption fail closed. Vault ciphertext limit is 24 MiB; persistence failure prevents plaintext release.
- Backups and device-transfer backup are disabled. Install compatible updates over the app; clearing storage/uninstalling loses access. Debug/release installs have separate vaults.
- No Internet or microphone permission. Sharing delegates ciphertext to another app; QR/camera operations remain local. This app does not route Internet traffic or operate a VPN.
- Screenshot protection covers app/scanner windows. It cannot stop an external camera. Drafts and displayed plaintext clear on backgrounding; keyboard learning/autofill are disabled by request. OS/keyboard compromise can still observe data.
- Plaintext, keys and strings exist in RAM during use. Some byte arrays are cleared, but Java cannot guarantee zeroization of every copy. Encrypted cache files are cleaned after 24 hours at the next launch. Exported/shared files remain wherever the user/provider saved them.
- Visible packet metadata includes group ID, sender/recipient fingerprints and approximate length. The carrier app knows delivery contacts/timing. There is no padding or transport-metadata protection.
- History is not pruned. Group reimport, data rollback or clearing storage resets replay protection. Clock manipulation or a modified client can bypass local expiry.

## Primary references

- [Android cryptography and OAEP behavior](https://developer.android.com/privacy-and-security/cryptography)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android document storage](https://developer.android.com/training/data-storage/shared/documents-files)
- [Camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- [HKDF specification and vectors, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869)
