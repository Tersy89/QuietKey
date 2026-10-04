# Enigma 0.4.1 security model

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

B creates a signed QKJ1 request containing public key, name, random 16-byte request ID and five-minute lifetime. A scans directly from B's Join group screen and explicitly confirms admission. A signs an updated member directory, then AES-encrypts the complete group details/secret/directory; its fresh AES key is RSA-wrapped only for B. QKG2's signed/GCM-authenticated header binds owner public key, B fingerprint, active request ID, expiry, wrapped key and nonce.

B verifies recipient, active request, lifetime, owner signature, GCM and signed directory before committing trust. All member public keys import together; no separate public-key scan is required. Used joining IDs are retained across local group deletion. Reimport refuses existing groups rather than erasing history. Optional QKA1 acknowledgement needs B's signing key and an already-approved pending request; it cannot add members.

The first owner identity is authenticated by the direct physical pairing context. A self-signed QR does not establish a person's identity or prevent someone substituting an entirely different owner's QR during initial pairing. A photograph of B's request is not proof of live presence, but the invitation can be decrypted only using B's private key and accepted only in that active request session. An observer photographing A's invitation cannot join using C's key. An authorized or compromised member can redistribute their group secret; software cannot stop this.

Owner-signed directories pin group/owner/expiry, distinct member keys/names and increasing revisions. Updates verify against the pinned owner key; old revisions do not roll back current trust, and conflicting equal revisions fail. Local blocked identities remain blocked unless the owner explicitly reinvites that device. Encrypted directory carriers in new messages/files automatically introduce approved members after packet authentication. Devices receive changes only when a packet reaches them; there is no network synchronization or immediate group-wide revocation.

Requests and large invitations cycle QKF1 frames; continuous scanning reassembles bounded unique frames in any order, with five-minute collection timeout, 64-frame/30,000-character limits (480 payload characters per frame) and complete SHA-256 transfer digest verification. That digest is not sender authentication. No partial frame modifies membership. Group size is limited to 30 devices.

## Password, biometric and stored-data protection

The app requires a 12–128-character passphrase. Argon2id v1.3 derives a 32-byte key with a 16-byte random salt, 64 MiB memory, three iterations and four lanes (Bouncy Castle 1.86 low-level implementation; no global provider replacement). Fixed parameters/bounded envelopes prevent attacker-controlled memory inflation. AES-GCM wraps a random 32-byte vault master key using that derived key; a non-exportable Keystore AES key encrypts the whole envelope again in password.qkl.

Vault format 2 uses the master key, fresh 12-byte nonce and AAD Enigma-vault-v2. groups.qkv stores group seeds, signed directories, aliases/blocks, joining/replay state, encrypted text and history metadata. Atomic synced/verified commits fail closed on corruption/missing keys. The vault ciphertext limit is 24 MiB. This is cryptographic encryption, not just a password UI check.

Optional biometric unlock uses AndroidX Biometric on API26+ with supported strong enrolled biometrics and is an explicitly enabled alternative. A separate per-operation authentication-bound Keystore AES key wraps the same master key. AndroidX BiometricPrompt/CryptoObject requests BIOMETRIC_STRONG and must authorize the cryptographic operation. GCM AAD and finalization occur only after successful authentication; cancellation cannot open the vault. Unsupported hardware or invalidated keys retain the passphrase route. Changed enrollment invalidates this route where supported. Password change disables it. Fresh password entry is still required for readable-file export, reset, password change and biometric configuration.

The RSA identity and device password-envelope key are not themselves configured for per-operation user authentication. Password protection encrypts the group secrets/local vault; it does not physically bind every RSA operation to the password. A compromised executing app can potentially invoke those Keystore keys. Hardware-backed storage is device-dependent. Password/bio authentication cannot make a compromised unlocked endpoint safe.

Unlock and fresh-auth wrong attempts have a persistent increasing UI delay capped at 60 seconds. It does not prevent offline guessing or a rooted/modified client bypassing delays. Strong passphrases and Argon2 address guessing cost. No password recovery or automatic wiping is provided.

## Session, history, expiry and exports

Leaving the app locks the session, clears UI and queues disposal of loaded secrets; screen-off always locks. Camera setup/document-picker handoffs have a maximum two-minute allowance; QR scanning allows up to five minutes, bounded by the joining lifetime. Session generation checks reject stale asynchronous results; readable export checks the session while writing. Missing/locked state refuses protected actions. FLAG_SECURE covers app/scanner windows; external cameras remain possible.

Java strings/keys/plaintext exist in RAM while unlocked. Byte arrays are cleared where practical; full zeroization of every copy is not guaranteed. Keyboard suggestions/learning and autofill are disabled by request, not a defense against a malicious keyboard.

History holds 200 encrypted entries and 150 MiB of encrypted packages with opaque UUID names. Eviction follows a successful vault commit. FileProvider exposes only ciphertext cache packages and is nonexported. Cache ciphertext is cleaned after 24 hours at next launch. Backups/device-transfer backups are disabled. No Internet, microphone or broad-storage permission; sharing delegates ciphertext to a user-selected external app. Enigma is not a VPN.

Text replay IDs are committed before display, bounded at 100,000 per group without pruning. Explicit local history reopening can reuse that authenticated entry's ID while rechecking signature/trust/expiry. File archives can be explicitly restored repeatedly and are marked previously opened. Reimport/rollback/reset can reset replay protection; there is no ordering guarantee.

TTL defaults to No expiry; options are five minutes, one hour, 24 hours, seven days and 30 days, clamped to separate group expiry. Signed/GCM timestamps are checked on text opening/history reopening/file inspection and again before readable export. now >= expiry refuses access; timestamps more than five minutes ahead are rejected. TTL uses local clocks, cannot recall saved plaintext, and can be bypassed by modified recipients with retained secrets.

File restoration streams directly to the chosen provider after verification and fresh authentication. Failure requests deletion of the partial destination; the provider may retain copies or fail deletion. Selecting cloud storage can upload readable content. Original, shared and restored files outside the app are not encrypted/deleted by Enigma. Visible packet metadata includes group ID, sender/recipient fingerprints, timing fields and approximate size; the external carrier knows delivery contacts/timing. No padding or transport-metadata protection.

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
