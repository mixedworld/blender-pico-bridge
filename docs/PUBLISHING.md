# Publication checklist

The public source repository is [mixedworld/blender-pico-bridge](https://github.com/mixedworld/blender-pico-bridge). Publication is manual: audit the intended source tree and release assets before pushing or publishing a new version.

## Review source

```sh
git status --short
git diff --cached --check
python3 tools/audit_publication.py
```

The audit reads the Git index. Stage intended changes before running it, and review the actual diff too. Do not force-add ignored evidence, recordings, third-party scenes, local tool installations or pairing files. Keep the source repository small; APK/ZIP builds belong in release assets rather than Git history.

## Build a release candidate

Follow [DEVELOPMENT.md](DEVELOPMENT.md), run Python and Kotlin checks, then run:

```sh
python3 tools/package_release.py
```

Review `dist/release/SHA256SUMS`, install the candidate APK on the emulator, and state the host platform and actual validation limits in release notes. Treat Windows as unverified until someone tests it. The debug signing key is local to its build machine; do not commit or upload a keystore.

Suggested first release title: **0.3.0 — Emulator preview**. Release assets are the add-on ZIP, debug APK and checksum file. Source can be downloaded from the repository/tag. The native scaffold currently retains its own Android `versionName` of 1.0; the add-on is 0.3.0. This preview is a paired development snapshot, not a promise of matching Android store versioning.

## Publish when GitHub is connected

Create the chosen repository under `mixedworld`, push the reviewed local `main` branch, and let CI run. Review successful CI and the source tree before publishing a tagged release with the verified assets. No workflow in this repository creates a remote, pushes changes, uploads models, or automatically publishes a release.

After the first public release exists, update README’s “Obtain the bridge packages” section to link to that real release. Do not add placeholder download links that look like existing releases. Enable private vulnerability reporting if available and add a maintainer contact to SECURITY.md.
