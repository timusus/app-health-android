# Maven Central Publishing Setup

## Overview

Publish `apphealth` library to Maven Central with CI/CD automation.

**Maven coordinates:** `com.simplecityapps:apphealth-android:0.1.0`

## Approach

- `maven-publish` plugin (built into Gradle)
- `signing` plugin (built into Gradle)
- `vanniktech/gradle-maven-publish-plugin` - simplifies Maven Central setup
- In-memory GPG signing (CI/CD compatible)

## Files to Change

### Root `build.gradle.kts`

Add vanniktech plugin.

### `apphealth/build.gradle.kts`

Add publishing configuration with POM metadata (name, description, license, developer, SCM).

### `.github/workflows/publish.yml`

Workflow triggered on GitHub release creation that publishes to Maven Central.

## Credentials

**Local development:** `~/.gradle/gradle.properties`
**CI/CD:** GitHub Secrets with `ORG_GRADLE_PROJECT_` prefix

Required secrets:
- `mavenCentralUsername` - Sonatype token username
- `mavenCentralPassword` - Sonatype token password
- `signingInMemoryKey` - Base64-encoded GPG private key
- `signingInMemoryKeyId` - 8-character GPG key ID
- `signingInMemoryKeyPassword` - GPG passphrase

## One-Time Manual Setup

1. Install GPG: `brew install gnupg`
2. Create GPG key: `gpg --full-generate-key`
3. Export and base64-encode: `gpg --export-secret-keys --armor KEY_ID | base64 | tr -d '\n'`
4. Upload public key: `gpg --keyserver keyserver.ubuntu.com --send-keys KEY_ID`
5. Verify Sonatype account has `com.simplecityapps` namespace
6. Generate Sonatype user token from account settings
7. Add secrets to `~/.gradle/gradle.properties` and GitHub Secrets

## Publishing

**Manual:** `./gradlew publishToMavenCentral --no-configuration-cache`

**Automated:** Create GitHub release → workflow publishes automatically
