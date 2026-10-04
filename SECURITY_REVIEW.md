# Enigma 0.3 security and joining review — 4 October 2026

Source review and focused tests of the current private prototype. This is not an independent audit. No application protocol or APK was changed by this review. The proposed pairing flow remains a product/security decision.

## Intended protection

Each group has an independent secret. The same A and B device identities may belong to multiple groups. Reading a private A→B packet should require both the intended endpoint's decryption key and that group's secret. C's compromise reveals C's usable private key and every group secret available to C, but must not reveal A→B content or permit forging A's signatures. This assumes A/B and their trusted key bindings remain uncompromised, and C was not a recipient of the content.

Source code can be public without revealing independently generated secrets. Keep this repository private until the owner explicitly authorizes publication.

Signatures authenticate a sender and packet; they do not hide plaintext. Recipient encryption provides confidentiality. The current RSA identity is used for both signing and decryption. A future key design should evaluate separating these roles with an explicit migration, rather than silently replacing identities or losing old access.

## Findings in the current implementation

1. **The group secret is not an independent decryption layer.** DirectProtocol.freshContentKey mixes fresh randomness with the group seed, then encrypt wraps the final key to A and B. Decrypt unwraps the final key without using the group seed. FileProtocol does the same. Tests confirm an endpoint with an all-zero replacement seed still opens these formats. This does not meet the intended two-secret requirement. Older packets cannot gain this protection retroactively; a new versioned format must require the group secret during reception as well as the endpoint private key. Use a carefully specified, reviewed design; HPKE's PSK modes provide a standard reference, not a claim that Enigma already implements HPKE.

2. **C cannot directly decrypt A↔B packets or sign as A with C's key.** Focused tests attempt raw RSA unwrap of both A/B key slots using C's private key, bypassing the UI's intended-recipient checks: both fail. Creating a packet naming A but signed with C's private key fails verification. This supports the specific third-member threat model, not a guarantee against every implementation flaw. C can still impersonate C, reveal its group secrets, forward content addressed to it and disrupt communication.

3. **Current invitations do not transfer the member directory.** Protocol.invite/accept transfers the group secret to one preselected device and installs only owner plus joiner keys. Other members therefore need separate trust scans. Replace this with an owner-signed directory containing group ID, owner key, names, member public keys and a directory revision. Bind each public key to its computed fingerprint. Pin the owner's key at initial direct pairing, verify every update against it, reject rollback and avoid silently substituting established identities. Names are owner assertions, not proof of real-world identity.

4. **First-contact authenticity still needs a trusted source.** A valid self-contained signature says the included key signed the invitation; it does not prove whose key it is. Scanning directly from the known owner's device can be the trusted pairing ceremony. Remote forwarded invitations need a separate trusted verification channel. Trusting the owner once can delegate member-key introduction to that owner and remove per-member fingerprint dialogs.

5. **No forward secrecy or recovery after compromise.** Retained A/B private keys can open their old captured packets. Adding a shared group layer will not protect against compromising a group-member endpoint that holds both secrets. Protecting old messages after endpoint compromise needs a reviewed ratcheting protocol and key deletion, with explicit history/recovery tradeoffs. MLS is a standard reference for group key updates and forward/post-compromise security; group-wide encryption alone does not provide recipient-private A↔B isolation from another current member.

6. **Removal is local, not revocation.** C keeps the old group secret and its private key after being removed on A. Replace/rotate the shared secret and distribute the replacement only to remaining approved devices; authenticate membership changes. A static invitation cannot be made globally one-use or revoked on disconnected devices by a local UI flag.

## Simpler joining without separate trust scans

The owner presses **Invite to group** and shows a QR. A joiner scans it and sees the group name, owner and member list together. The directory is authenticated by the owner's signature; each device generates and retains its own private keys. Never transfer another device's private key.

Two distinct admission policies are possible:

- **One-scan bearer invitation:** the QR contains the group secret and signed public directory. Scanning grants group access. Anyone who copies the QR learns the secret and can share it; a short display timer cannot undo that. The new device can learn existing keys immediately, but the owner cannot learn a never-transmitted joiner's public key. The joiner must send/show a signed join-back packet, and an owner-signed directory update must introduce it to existing members. Possessing the QR must not let someone replace A/B keys or forge an owner-signed directory.
- **Owner-approved, confidential invitation:** the owner's first QR introduces the group/owner and a short-lived challenge, without releasing the group secret. The joiner returns its public key and signed request by QR. The owner approves that device and returns an encrypted QR containing the group secret and signed directory. The app handles key registration automatically inside this one guided flow. Offline this needs a final owner-to-joiner transfer; a single one-way QR cannot encrypt a secret to a public key the owner does not yet know.

The earlier choice prompt abbreviated the owner-approved flow. The final encrypted transfer is necessary if the secret must remain confidential until approval. A shared-secret QR followed by approval can control the official member directory, but cannot keep the secret from an unapproved scanner.

Existing members need authenticated directory updates when someone joins. An offline static QR cannot synchronize phones that never receive the update. The update may be carried as a QR or encrypted package, with automatic verification/import rather than separate member trust steps.

A single QR has limited capacity (about 3 KB at the largest version and lowest correction level). A growing directory of RSA public keys will require multiple QR frames or a file transfer. The UI should collect a complete bounded transfer, show progress, verify the whole signature, then commit atomically; partial scans must not modify trust. Do not promise unlimited membership in one static QR.

## Executed checks

The complete JVM suite passed on 4 October: 39 JUnit entries / 59 portable checks (the original 21 checks run through one entry). Five new SecurityReviewTest methods cover raw C-key unwrap failure, C-signature impersonation refusal, the current missing group-secret requirement, independent secrets with reused identities, and forged/non-owner invitation refusal. Existing text/file suites include signature/GCM tampering, expiry and third-member rejection.

No physical-phone pairing test or independent audit was performed in this review. The installed/downloadable APK remains 0.3. Protocol changes and the replacement joining flow have not been implemented yet.

References: [HPKE, RFC 9180](https://www.rfc-editor.org/rfc/rfc9180.html), [MLS, RFC 9420](https://www.rfc-editor.org/rfc/rfc9420.html), [QR capacity, DENSO WAVE](https://www.qrcode.com/en/faq.html).
