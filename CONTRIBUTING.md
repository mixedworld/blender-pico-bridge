# Contributing

Start with the [build guide](docs/DEVELOPMENT.md) and [protocol](PROTOCOL.md). Keep the Blender exporter/transport separate from the native scene, animation and inspection transforms.

Before opening a pull request:

- Run the Python tests and Gradle unit/build checks described in the build guide.
- Run `python3 tools/audit_publication.py` and inspect the staged diff.
- Verify an affected user flow on the PICO Emulator and state the host OS/architecture. Do not describe build-only or seeded screenshot checks as hand-input testing.
- Include source and documentation changes; keep generated APKs, tokens, SDKs, captured rooms and third-party models out of commits.
- Keep the SDK version and generated application/launch chain stable unless a migration is explicitly part of the change. Use public PICO APIs and preserve bundled asset provenance.

Original contributions are accepted under the project’s MIT license. Retain third-party notices and avoid introducing content without redistribution rights.
