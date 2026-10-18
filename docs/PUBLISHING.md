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

Release versions must match between add-on `bl_info`, Android `versionName` and the tag; increment Android `versionCode` for updates. Version 0.8.0 uses versionCode 8. Release assets are the versioned add-on ZIP, universal debug APK, `render-worker-setup.zip`, `third-party-notices.zip` and `SHA256SUMS`. Source is available through the tag. Include the third-party notices with redistributed APKs.

For 0.8.0, use the README’s Room Cube path and keep experimental device instructions generic. Do not publish device-specific claims, local calibration payloads or private test assets.

## Publish when GitHub is connected

Merge the reviewed feature branch into `main` in `mixedworld/blender-pico-bridge` and let CI run. Review successful CI and the source tree before publishing a tagged release with the verified assets. No workflow in this repository creates a remote, pushes changes, uploads models, or automatically publishes a release.

Prepare README links for the intended release version, then verify that the published release and all linked assets exist before announcing completion. Never leave links to unpublished placeholder packages in a completed release. Private vulnerability reporting is enabled; SECURITY.md links to the private reporting form.
