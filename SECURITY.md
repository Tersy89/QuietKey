# Enigma 1.0.0 security model

Development prototype, not independently audited. Nothing here is a security audit. See SYSTEM_DESIGN.md for the implemented architecture and formats and VERIFICATION.txt for the tests actually executed. SECURITY_REVIEW.md records the historical 0.3 findings.

An illustrated version with diagrams is [docs/security.html](https://htmlpreview.github.io/?https://github.com/Tersy89/QuietKey/blob/main/docs/security.html) ([source](docs/security.html)). Step-by-step use is in the [user guide](https://htmlpreview.github.io/?https://github.com/Tersy89/QuietKey/blob/main/docs/user-guide.html).

## At a glance

### Five layers between an attacker and your message

| Layer | What it does | Protects |
|---|---|---|
| **Unlock** | Passphrase or fingerprint, then a 4-digit PIN on Enigma's own number pad. 5 wrong PINs or the panic PIN erase everything. | The phone at rest |
| **Vault** | Identity keys, key pools, group secrets and opened history, AES-256-GCM encrypted under the vault master key. | The phone at rest |
| **Joining in person** | QR code or NFC tap, matching check codes, owner-signed invitation encrypted only to the joining phone. | Who is trusted |
| **Group secret** | A 32-byte secret shared only by members, mixed into every message key. Outsiders cannot derive any key. | Keeps outsiders out |
| **Per-recipient message key** | One-time key, used once and deleted. X25519 + ML-KEM-768 together, signed with Ed25519 + ML-DSA-65. | Each message in transit |

### Keys each phone holds

| Key | Algorithms | Lifetime | Job | Size |
|---|---|---|---|---|
| **Identity** | Ed25519 + ML-DSA-65 signing, X25519 agreement | Until you start fresh | Proves who you are. Exchanged only in person. Fingerprint = SHA-256 of all three public keys. | 2,016 B public |
| **One-time keys** | X25519 + ML-KEM-768 | One message | Each message to you uses one; its private half is deleted after the message is opened. 100 held per contact. | 1,312 B each |
| **Backup key** | X25519 + ML-KEM-768 | Replaced every 30 days, kept 365 more | Used only when a sender has run out of your one-time keys ("Reduced protection"). | 1,312 B |
| **Owner reserve** | X25519 + ML-KEM-768 | Until a new member joins | 20 public one-time keys each member gives the owner, so later members can message you at full protection from the first message. | 20 x 1,312 B |
| **Group secret** | 256-bit random value | Life of the group | Salt for every message key and the key for the member list carried in messages. | 32 B |
| **Vault master key** | AES-256-GCM | Life of the installation | Encrypts everything above on the phone. Released only by passphrase or fingerprint plus PIN. | 32 B |

Signatures carry both halves: 64 bytes of Ed25519 followed by 3,309 bytes of ML-DSA-65. Both must verify or the packet is rejected.

### What an attacker gets

| Scenario | Result | Why |
|---|---|---|
| Intercepts the `.enigma` file in a messenger | Nothing readable | Needs a recipient's one-time private key and the group secret. |
| Breaks X25519 or Ed25519 (new maths or a quantum computer) | Still protected | ML-KEM-768 and ML-DSA-65 still have to be broken too. |
| Finds a flaw in ML-KEM or ML-DSA | Still protected | X25519 and Ed25519 still stand. The new algorithms are young, which is why they are paired. |
| Group member who was not a recipient | Nothing readable | Has the group secret but not the recipients' one-time keys. |
| Photographs the joining QR code | Nothing readable | The QR key only protects the local link; the invitation is encrypted to the joining phone, and the check codes would not match. |
| Steals a locked phone | Nothing readable | Needs passphrase or fingerprint, plus the PIN. |
| Copies the app's files off the phone | Nothing readable | The vault needs both steps, and the unlock files are wrapped by a key that cannot leave the phone's security hardware. |
| Steals the phone later and breaks in | New messages only | Old messages' one-time keys are gone. Stored history and future messages are exposed, and the thief can pose as you until you start fresh and rejoin. |
| A keyboard app records typing | Passphrase at risk | The PIN uses Enigma's own number pad; the passphrase and typed messages still go through the keyboard. |
| Forces you to unlock | Your choice | Enter the panic PIN and Enigma erases itself. |
| Root access plus your passphrase or fingerprint | PIN can be guessed | Root can use the security key to try all 10,000 PINs without the wrong-PIN count. |
| Malware on an unlocked phone | Exposed | No app can protect against a compromised operating system while in use. |
| Watches the messenger traffic | Metadata only | Who sends files to whom, when, and how large. Not the content. |
| Sends a damaged or old-format file | Nothing opens | Lengths and signatures are checked first; one plain explanation is shown. |

### Unlock and wipe in one picture

```
Step 1 (either):  passphrase --Argon2id--+
                  fingerprint (Keystore) -+--> first-step secret --+
Step 2 (always):  4-digit PIN --Argon2id-----------------------------+--> HKDF --> vault master key
5 wrong PINs, or the panic PIN --> delete Keystore keys first, then every file
```

## Intended protection

Each installation has a hybrid identity made on the phone: Ed25519 and ML-DSA-65 for signing, X25519 for key agreement. Each group has an independent random 32-byte secret. A device identity can join several groups. A message or file is encrypted for one or more selected members of one group; each recipient's copy of the key is wrapped under a key only that recipient can rebuild. Missing, self or unknown recipients are refused before output.

What a message needs to be read:
- one of the recipient's one-time private keys (X25519 **and** ML-KEM-768 halves), or their backup key in the reduced-protection case;
- the group secret.

What a message needs to be forged: both an Ed25519 and an ML-DSA-65 signature from the sender.

Consequences:
- Someone who intercepts a `.enigma` file learns only sizes, timing and key IDs.
- A group member who was not a chosen recipient holds the group secret but not the recipients' one-time keys, and gets "not addressed to you".
- Breaking one algorithm family (X25519/Ed25519, or ML-KEM/ML-DSA) is not enough.
- Each one-time key is used for one message and its private half is then deleted (forward secrecy). If a sender has run out of a recipient's one-time keys it uses that recipient's backup key, and both phones mark the message "Reduced protection". Backup keys are replaced every 30 days; an old private half is kept 365 days because a roster can hand out a member's backup key long after it was replaced.
- The sender's signature covers the group, roster revision, sender, the full recipient list, key IDs and a content hash, and sits inside the encryption, so a recipient cannot re-wrap a signed message for someone else.

A trusted owner authenticates member introductions but is not automatically a recipient of every message. A malicious owner can introduce deceptive names or devices; verify the known owner when first pairing and keep fingerprints visible.

No protection is promised for plaintext on a compromised unlocked phone, a recipient forwarding plaintext, or a compromised OS or keyboard. There is no ratchet, post-compromise recovery, owner transfer or key recovery. A thief who unlocks the phone can impersonate its owner until the owner is told and the member rejoins with new keys.

## Algorithms and bounded formats

- Key agreement: X25519 (X3DH-style, four Diffie-Hellman results) **plus** ML-KEM-768 (FIPS 203), combined with HKDF-SHA-256 using the group secret as salt (info "Enigma/PQX3DH/v1"). Both must hold.
- Signatures: Ed25519 (64 bytes) followed by ML-DSA-65 (FIPS 204, 3,309 bytes). Both must verify. Backup and one-time keys carry an Ed25519 signature only, to keep key pools small, and only travel inside packets with a hybrid signature or inside the owner-signed roster.
- Content: AES-256-GCM with fresh nonces and 128-bit tags. HKDF-SHA-256 for derivation. Bouncy Castle 1.86 supplies the primitives; no global provider is installed.
- Randomness: Android SecureRandom is the foundation. Camera digests (at setup and group creation) and accelerometer and gyroscope readings (while unlocked) are mixed in with HKDF. They can only add unpredictability; they are not a measured entropy guarantee. The vault master key and the PIN salt use SecureRandom only.
- Formats: ENG5 (messages, file `.enigma` or `ENG5:` text), QKJ4 / QKG4 / QKA4 (joining request, invitation, receipt), QKB1 (the QR/NFC bootstrap), EJW1 (local joining frames), vault format 3. Big-endian numbers, Java modified UTF-8 strings, length-prefixed bounded blobs, strict versions and trailing-data checks.
- Limits: 50 MiB content, up to 50 recipients and 30 devices per group. Outer message header 256 KiB, inner header 2 MiB, per-recipient entry 4,096 bytes. Join request 64,000, invitation 600,000, receipt 200,000 characters, roster 256 KiB. Whole message content is held in memory while sealing or opening (no chunked streaming yet).
- Public fingerprints are the SHA-256 of all three public keys (2,016 bytes).

A file or text that cannot be parsed (older format, other keys, damaged) opens nothing and shows one plain explanation.

## Owner-managed joining and trust

The joining phone makes a signed QKJ4 request: its public keys, name, a random 16-byte request ID, its backup key and 20 reserve public keys for the owner to hand to later members, with a five-minute lifetime. The owner confirms in person, then sends a QKG4 invitation: the group secret, settings, an owner-signed roster, and 100 one-time public keys for the joiner. The invitation is encrypted to the joining phone only, with X25519 (ephemeral and static) plus ML-KEM-768 to the joiner's backup key (info "Enigma/invite/v4"), and carries the owner's hybrid signature. The joiner verifies the request, owner, lifetime and roster before committing trust, then returns a signed QKA4 receipt with 100 one-time public keys for the owner. A receipt cannot add members.

The first owner identity is authenticated by the physical pairing context. Nearby joining pins the owner fingerprint carried in the scanned QKB1 code; manual transfer relies on comparing a six-digit check code. Neither establishes a person's real-world identity or stops someone presenting an entirely different owner's code during first pairing. A photograph of a request is not proof of live presence, but the invitation can be decrypted only with the joiner's private keys and accepted only in that active request. An authorized or compromised member can redistribute the group secret; software cannot stop this.

The owner-signed roster pins group, owner, expiry, distinct member keys and names, and increasing revisions. Updates verify against the pinned owner key; old revisions do not roll back trust and conflicting equal revisions fail. When a member joins later, the owner's roster introduces them to existing members with one reserve key each way, so they can message each other at full protection from the first message. The roster travels inside messages, so devices learn of changes only when a message reaches them; there is no network synchronisation or immediate group-wide revocation.

### Layering: joining is separate from message encryption

Joining only delivers the group secret, the owner-signed roster, settings and key supplies to one approved device. Messages then use their own per-message keys. No joining key is ever used for message content, and no message key is used for joining.

During nearby joining the group secret is protected by three independent layers, outermost first:

1. **Wi-Fi link:** WPA2/WPA3 of the temporary local-only hotspot (or shared private Wi-Fi). Not relied on: anyone who sees the QR learns the hotspot password, and a spoofed access point is assumed possible.
2. **One-time transport key (KEK):** 32 random bytes made by the owner per invitation and carried only in the single QR/NFC tap. HKDF-SHA-256 derives the EJW1 frame key; every frame is AES-256-GCM with a fresh nonce and authenticated header. A spoofed hotspot or on-path device without the KEK cannot read or alter frames.
3. **Recipient encryption (QKG4):** encrypted to the joining phone's own X25519 and ML-KEM keys and signed by the owner. Someone who photographed the QR and knows the KEK still cannot decrypt it, and cannot forge one, because the recipient refuses an invitation whose owner differs from the fingerprint in the scanned code.

Manual file transfer omits layers 1 and 2 and relies on layer 3 plus the check code, so those files can travel over any medium.

### Nearby joining: single QR or NFC tap, then Wi-Fi

The owner's app starts an Android local-only hotspot (no Internet, random name and password, closed when joining ends) or, if chosen, uses the current private Wi-Fi. It binds a TCP listener to that interface's private address only (port 45873, or another if busy) and builds QKB1: mode, hotspot name, password, security type, owner address and port, the 32-byte KEK, the owner's fingerprint and a five-minute expiry. QKB1 is shown as one QR code and served by an NFC host card emulation service that answers only Enigma's own AID, needs an unlocked phone, and returns "not found" whenever no invitation screen is open. The payload is cleared when the first verified request arrives or the screen closes.

The recipient scans the QR (ZXing, secure window) or reads it in NFC reader mode, verifies the fields and expiry, and connects through Android's Wi-Fi network-request API (one system prompt; app-scoped, no Internet). Only literal private IPv4 endpoints are used; no discovery, DNS or backend request is made.

Both screens show a six-digit check code: HKDF(KEK, SHA-256(exact signed request), "check-code"). The owner invites only if the codes match. Someone who photographed the QR and connected first would produce a code that differs from the real recipient's screen, because the real recipient's fresh request is not known in advance. The owner accepts one verified request per session and at most five failed initial connections; reads before approval time out after ten seconds.

EJW1 framing has magic, mode, packet type, strict per-direction sequence and bounded length. Only request, invitation, signed receipt, done is valid; downgrade, reflected or wrong-type frames, malformed text, oversize input, truncation and repeated sequence fail. Listeners, sockets, hotspot, network request, NFC payload and pending approvals close on Cancel, lock, Activity destruction and the five-minute deadline.

Android needs Location (Android 8-12) or Nearby Wi-Fi devices (13+, flagged neverForLocation) permission to start the local-only hotspot. Enigma asks only when inviting and never reads location or scan results. IP addresses, timing and sizes remain visible on the local network.

### Manual file transfer

The joiner saves its signed request as a text file; the owner opens it, approves and saves the recipient-encrypted invitation; the joiner opens it and may save a signed receipt. Both screens show a six-digit check code, SHA-256("Enigma/manual-check/v1" || request || owner public key); compare it in person or on a call. Request files expire five minutes after creation.

## Unlock, PIN and stored-data protection

Opening Enigma takes two steps.

**Step 1: passphrase or fingerprint.** The passphrase is 12-128 characters. Argon2id v1.3 derives a 32-byte key (16-byte random salt, 64 MiB, three iterations, four lanes; fixed parameters and bounded envelopes prevent attacker-controlled memory inflation). It unlocks a random 32-byte first-step secret; a non-exportable Android Keystore AES key (alias quietkey.vault.v1) wraps that envelope again in password.qkl. Optional biometric unlock uses a separate authentication-bound Keystore key through AndroidX BiometricPrompt and CryptoObject, requesting BIOMETRIC_STRONG; cancellation cannot open the vault. Changed enrolment invalidates it where supported, and changing the passphrase turns it off.

**Step 2: 4-digit PIN.** The vault master key is AES-GCM encrypted under HKDF(first-step secret, Argon2id(PIN)). Neither step alone opens the vault. The PIN is typed on Enigma's own number pad, so the phone keyboard is not used for it.

**Wipe.** The PIN state (attempt count, panic PIN, check value, encrypted master key) lives in files/pin.qkp under the Keystore key. Each attempt is counted on disk before the PIN is checked, so force-closing the app does not give extra tries; a correct PIN resets the count. After five wrong PINs, or if the panic PIN (default 9999, changeable, never equal to the PIN) is entered, Enigma deletes its Keystore keys first, then its files, cache and preferences, and asks Android to clear its data. Android does not let an app uninstall itself, so an empty app remains. Wrong PINs count only after step 1 succeeds, so a stranger holding the phone cannot erase data without the passphrase or fingerprint.

**Vault.** groups.qkv (vault format 3) holds identity keys, key pools, group secrets, rosters, aliases, joining state and message metadata under AES-256-GCM with the master key, a fresh 12-byte nonce and AAD "Enigma-vault-v2". Writes are atomic, synced and verified; corruption or a missing key fails closed. The ciphertext limit is 24 MiB. Opened content is stored in files/history, each file AES-GCM encrypted under a key derived by HKDF from the master key and the file name; view-once content is never stored. Outgoing packages stay under files/packages for Share again.

**Older data.** A vault from 0.6.0 (format 1) or an early 0.7.0 build (format 2) is detected and kept unchanged. Enigma shows "New keys needed", and only an explicit, passphrase-confirmed Start fresh replaces it. Installs from before the PIN are asked to set one first, which also replaces the first-step secret and turns biometric unlock off until it is enabled again.

Unlock and fresh-authentication wrong passphrase attempts also have a persistent increasing delay capped at 60 seconds. That does not stop offline guessing or a modified client; Argon2id and a strong passphrase do. There is no recovery of a forgotten passphrase.

The device Keystore keys are not configured for per-operation user authentication (except the biometric key). A compromised executing app could potentially use them. Hardware-backed storage is device dependent.

## Session, history, expiry and exports

Leaving the app locks the session, clears the UI and queues disposal of loaded secrets; screen-off always locks. Camera setup and document-picker handoffs have a two-minute allowance; scanning the joining code up to five minutes; the Wi-Fi prompt 90 seconds. Locking cancels nearby transport and pending approvals. Stale asynchronous results are rejected by a session counter. FLAG_SECURE covers the app, scanner and nearby windows; external cameras remain possible.

Java strings, keys and plaintext exist in RAM while unlocked. Byte arrays are cleared where practical; full zeroisation is not guaranteed. Keyboard suggestions and autofill are disabled by request, which is not a defence against a malicious keyboard.

History keeps 200 items and up to 150 MiB of encrypted packages, with opaque names. FileProvider exposes only ciphertext cache packages and is not exported; cache is cleaned after 24 hours. Backups are disabled. Enigma holds Android's INTERNET permission because the local joining connection is a network socket, and there is no permission limited to the local network; messages never use the network and leave the app only as files or text the user shares. There is no microphone or broad-storage permission.

Expiry defaults to none; choices are five minutes, one hour, 24 hours, seven days and 30 days, clamped to the group's expiry. The signed timestamp is checked on opening, reopening and saving; timestamps more than five minutes ahead are rejected. Expiry uses device clocks, cannot recall saved plaintext, and can be bypassed by a modified recipient app. View once keeps no copy in Enigma, but cannot stop a second camera.

A one-time key's private half is deleted only after the message is authenticated and decrypted, so replaying a one-time-key message fails because the key is gone. Backup-key messages use a replay log.

In-app viewing: images up to 25 MiB and text up to 1 MiB are restored into an in-memory buffer that is zeroed after decoding, and live only in the secure window while open. Nothing readable is written to storage. Image decoding uses Android's decoder on authenticated content from a verified member, so a malicious member could still target decoder bugs. **Save to device** needs the passphrase and writes a readable copy outside Enigma; cloud storage destinations can upload it, and Enigma cannot delete it afterwards.

Opening from other apps: Enigma accepts ACTION_VIEW and ACTION_SEND for `.enigma` content and text, only `content://` URIs from other apps, and only after unlock; content goes through the same bounded copy and verification as a picked file.

Visible message metadata includes the group ID and opaque key IDs; the carrier app knows who sent a file to whom, when and how large. There is no padding or transport-metadata protection.

## Removal and reset

Removing or blocking a member deletes the keys you hold for them, including your unused one-time private keys issued to them, unless they are still trusted in another group on the phone (keys are per person, not per group). Deleting a group or adopting a roster that drops someone does the same. It does not erase secrets already held on their phone. For revocation, create a fresh group and invite only remaining members. Automatic rekeying, a second admin and a group-update button are not implemented.

Reset requires a fresh passphrase and the exact typed RESET, then calls Android's clearApplicationUserData, destroying this installation's keys, vault, history, cache, setup, permissions and URI grants while keeping the APK. Files outside the app remain. Android Settings offers the destructive reset after a forgotten passphrase. Neither reset nor deletion guarantees physical flash erasure.

## Limits that remain

- The design and code are not independently audited. Enigma's own combinations (group secret in the key, signed one-time keys, owner reserve, multiple recipients) are not standard protocols.
- The hybrid algorithms guard against one family failing. They do not protect against bugs in Enigma, Bouncy Castle or Android, or against a compromised phone. ML-KEM and ML-DSA are newer than X25519 and Ed25519.
- Forward secrecy depends on deleting keys; flash storage and Java memory cannot guarantee erasure. Backup-key messages have weaker forward secrecy until that backup key is replaced.
- Stored history is exactly as safe as the vault unlock.
- Someone who controls the phone at root level and also has the passphrase or a fingerprint can bypass the wrong-PIN count: root can use (not extract) the Keystore key to read pin.qkp and then try all 10,000 PINs, each costing one Argon2id run. The PIN protects a phone that is handed over or taken while running the normal Android system.
- Backup and one-time keys carry an Ed25519 signature only. A group owner who could also forge Ed25519 could swap another member's keys in a roster; that needs an insider and a broken Ed25519, and reading messages would still need X25519 or ML-KEM to fail as well.
- AES-256-GCM is the only content cipher. It is not considered at risk from quantum computers, so no second symmetric layer was added.
- Physical vendor behaviour (hotspot start, network-request prompt, NFC card emulation) is a manual check, not emulator evidence.

## Primary references

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) and [cryptographic biometric authentication](https://developer.android.com/identity/sign-in/biometric-auth)
- [Argon2, RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html) and [HKDF, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869)
- [FIPS 203 (ML-KEM)](https://csrc.nist.gov/pubs/fips/203/final) and [FIPS 204 (ML-DSA)](https://csrc.nist.gov/pubs/fips/204/final)
- [Signal X3DH](https://signal.org/docs/specifications/x3dh/) and [PQXDH](https://signal.org/docs/specifications/pqxdh/)
- [Android local-only hotspot](https://developer.android.com/reference/android/net/wifi/WifiManager), [Wi-Fi network request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap) and [host card emulation](https://developer.android.com/develop/connectivity/nfc/hce)
- [Local network permissions](https://developer.android.com/privacy-and-security/local-network-permission)
