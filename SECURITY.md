# Security and local data

This prototype transports model geometry, materials and animation over local HTTP authenticated with a bearer token. HTTP is not encrypted. Prefer ADB reverse forwarding; do not forward port 8766 from a router or expose it publicly.

The Blender server listens on local interfaces while Start Bridge is active. Stop Bridge when finished. The viewer stores pairing in its application-private preferences; it does not upload scenes to a cloud service. The optional launch helper passes credentials through Android intent extras, so use it only on a trusted development host.

`.pairing.json`, local SDK paths, signing keys, `.blend` files, exported models, logs and recordings must stay out of Git and release packages. Blender may persist add-on settings—including tokens—when saving a scene; use a separate public copy with pairing cleared before sharing a `.blend`. The GLB exporter disables arbitrary custom-property export, and the bridge exposes no general-purpose Python execution endpoint.

The experimental source-hide command only accepts one boolean and expires unless renewed. It is separate from the emulator-only getting-started workflow.

Do not open a public issue containing a working token or private model. Stop the bridge, clear/replace its token and re-pair if a credential is exposed. Private vulnerability reporting is enabled: [report a vulnerability privately to the maintainers](https://github.com/mixedworld/blender-pico-bridge/security/advisories/new).
