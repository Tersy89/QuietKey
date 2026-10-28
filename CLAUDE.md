@AGENTS.md

## Claude Code notes

- Build with `.\tools\dev.ps1 build`. JDK: Android Studio's bundled runtime (`C:\Program Files\Android\Android Studio\jbr`); SDK: `%LOCALAPPDATA%\Android\Sdk`.
- Pure-Java protocol classes (Protocol, PairingProtocol, DirectProtocol, GroupKey, JoiningFlow, JoinBootstrap, LocalJoinWire) have no Android dependency and can be tested quickly with JVM unit tests under `app/src/test`.
- Joining (bootstrap → local transport → recipient-encrypted invitation) and message encryption are separate layers. Never let a transport key stand in for the recipient-encrypted QKG2 invitation.
