# Enigma development instructions

- Keep this repository private. Publishing or making it public requires the owner's explicit request.
- Do not modify the parent project's synced `sources/` reference files.
- Read README.md, SECURITY.md, TESTING.md and VERIFICATION.txt before development.
- Maintain SYSTEM_DESIGN.md in the same change as alterations to architecture, protocols, keys, storage, authentication, joining, user workflows or security limits. Describe implemented behavior separately from planned work, update its version/change log and cross-check it against code and executed tests.
- Update SECURITY.md, TESTING.md and VERIFICATION.txt when the implementation or verification changes. Record tests actually executed and remaining manual checks. Never describe tests as a security audit.
- Keep the downloadable test APK at the root of main as Enigma.apk when producing an authorized private update. Never commit local keys, device data, tools, logs or previews.
- This is a development prototype; older data/key compatibility is not a release requirement unless the owner asks for it. Do not silently destroy a retained phone installation when testing. Use the preserving phone helper; destructive tests belong on an identified disposable emulator.
