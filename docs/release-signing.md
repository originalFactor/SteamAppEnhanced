# Production signing and builds

The application ID is `cc.ptoe.steam.enhanced`. Release APKs and bundles use the
`steam-enhanced-release` RSA-4096 key in a PKCS12 keystore. The public certificate
is [release-signing-certificate.crt](release-signing-certificate.crt).

Certificate SHA-256:

```text
36602657244da1c3a703ef4f0eb0cc1adc472f1cd585901d50bb31f0f1b470ad
```

## Local builds

Private files are ignored by Git:

- `.signing/steam-enhanced-release.p12`: encrypted keystore, permissions `0600`.
- `signing.properties`: keystore path, alias and passwords, permissions `0600`.

Keep both files together in a secure backup. The checked-in
`signing.properties.example` describes the local configuration format. Signing
environment variables take precedence over the properties file.

```sh
./gradlew :app:assembleRelease :app:bundleRelease \
  :app:testDebugUnitTest :app:lintRelease \
  --no-configuration-cache --no-build-cache
```

Outputs:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

Override versions with `-PreleaseVersionName=1.1.0 -PreleaseVersionCode=2`.
Use a version code higher than every previously distributed build. Debug builds
work without signing credentials; release builds require a complete signing
configuration and an existing keystore.

## GitHub Actions

Repository: `originalFactor/SteamAppEnhanced`.

| Actions secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Private key password |

The runner restores the key into its temporary directory and exposes passwords
only to the Gradle build step. Signing builds disable Gradle build/configuration
caches and the setup-gradle cache. An unconditional final step removes the key.
All third-party Actions are pinned to their reviewed commit SHAs.

**Build and test** runs on pushes to `main`, pull requests and manual dispatch.
It builds a debug APK, runs JVM tests and Android Lint, and uploads the APK and
reports as artifacts. Debug artifacts are retained for 14 days.

**Signed production build** runs for `v*` tags and manual dispatch. Its optional
inputs are `version_name` and `version_code`. Version names default to the tag
without `v`, or `1.0` for a manual run; version codes default to that workflow's
run number. Specify an explicit version code when an earlier distributed build
used a higher code.

The production workflow builds signed APK/AAB files, runs JVM tests and release
Lint, verifies APK and bundle signatures, and checks the APK certificate against
the public production certificate. Its artifact contains versioned APK/AAB
files, `SHA256SUMS` and the public certificate, retained for 30 days.

```sh
gh workflow run release.yml --repo originalFactor/SteamAppEnhanced \
  -f version_name=1.0.0 -f version_code=1
```

Android's signing model is described in the
[Android app signing documentation](https://developer.android.com/studio/publish/app-signing).
GitHub documents the encrypted repository storage in
[Using secrets in GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
