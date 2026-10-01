# Changelog

MiniPic follows a lightweight release history. Detailed device and verification notes remain in [`docs/maintenance/DEVELOPMENT_STATUS.md`](docs/maintenance/DEVELOPMENT_STATUS.md).

## [0.0.7] - 2026-10-01

### Added

- Continuous manga reading mode with natural numeric filename ordering and visible-page bitmap loading.
- GIF and animated WebP playback lifecycle handling in the image viewer.
- Android instrumentation regression coverage for OWW211/API 30.
- Configurable crown sensitivity from 10% to 200%, with a 40% default.
- GitHub-ready repository metadata, external signing configuration, and release verification documentation.

### Changed

- Ordinary image transitions now use a 150 ms vertical animation.
- Crown paging accumulates the sensitivity-scaled delta to a 100-unit threshold and immediately uses the same switching path as touch input.
- Gallery covers use `RecyclerView` and asynchronous decoding with bounded caching.
- File copy, move, and delete flows preserve the source image when a provider or permission operation fails.

### Verification

- 36 JVM unit tests passed.
- 12 OWW211 instrumentation tests passed.
- Android Lint completed with 0 errors and 16 warnings.
- Debug and signed Release APKs built successfully.
- Universal, `armeabi-v7a`, and `arm64-v8a` APKs passed `apksigner verify` with the long-term release certificate.

### Known limitations

- The primary validation target is OPPO Watch OWW211 on Android 11; other watch models need separate validation.
- Physical crown direction, detents, and feel still require manual acceptance.
- `MANAGE_EXTERNAL_STORAGE` has distribution restrictions, especially for Google Play.
