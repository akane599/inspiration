# CI/CD — Android / KMP — GitHub Actions

## Workflow Structure

```
.github/
├── workflows/
│   ├── build.yml        ← PR validation: compile + lint
│   ├── test.yml         ← PR validation: unit + integration tests + coverage
│   ├── release.yml      ← Tag push: build + sign + upload to Play/TestFlight
│   └── nightly.yml      ← Scheduled: instrumented tests on Firebase Test Lab
├── actions/
│   └── setup-android/
│       └── action.yml   ← Composite: JDK + Gradle cache + secrets
```

---

## setup-android Composite Action

```yaml
# .github/actions/setup-android/action.yml
name: Setup Android
description: JDK, Gradle cache, and optional signing config

inputs:
  java-version:
    default: "21"
  sign:
    default: "false"
  keystore-base64:
    required: false
  keystore-password:
    required: false
  key-alias:
    required: false
  key-password:
    required: false

runs:
  using: composite
  steps:
    - name: Set up JDK
      uses: actions/setup-java@v4
      with:
        java-version: ${{ inputs.java-version }}
        distribution: temurin
        # No cache: gradle here — gradle/actions/setup-gradle manages the cache

    - name: Setup Gradle
      uses: gradle/actions/setup-gradle@v4
      with:
        cache-read-only: ${{ github.ref != 'refs/heads/main' }}
        gradle-home-cache-cleanup: true

    - name: Decode keystore
      if: inputs.sign == 'true'
      shell: bash
      run: |
        echo "${{ inputs.keystore-base64 }}" | base64 --decode > $RUNNER_TEMP/release.jks

    - name: Write signing config to local.properties
      if: inputs.sign == 'true'
      shell: bash
      run: |
        cat >> local.properties <<EOF
        KEYSTORE_PATH=$RUNNER_TEMP/release.jks
        KEYSTORE_PASSWORD=${{ inputs.keystore-password }}
        KEY_ALIAS=${{ inputs.key-alias }}
        KEY_PASSWORD=${{ inputs.key-password }}
        EOF
```

---

## build.yml — PR Validation

```yaml
name: Build

on:
  pull_request:
    branches: [main, develop]
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  checks: write

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-android:
    name: Android Build
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - uses: actions/checkout@v4

      - uses: ./.github/actions/setup-android

      - name: Compile common code
        run: ./gradlew composeApp:compileCommonMainKotlinMetadata --no-daemon

      - name: Assemble debug APK
        run: ./gradlew composeApp:assembleDebug --no-daemon

      - name: Run lint
        run: ./gradlew composeApp:lintDebug --no-daemon

      - name: Upload lint report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-report
          path: composeApp/build/reports/lint-results-debug.html

  build-ios:
    name: iOS Framework
    runs-on: macos-15
    timeout-minutes: 45

    steps:
      - uses: actions/checkout@v4

      - name: Cache Kotlin/Native compiler
        uses: actions/cache@v4
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('gradle/libs.versions.toml') }}
          restore-keys: konan-${{ runner.os }}-

      - uses: ./.github/actions/setup-android

      - name: Build iOS framework (simulator)
        run: ./gradlew composeApp:linkDebugFrameworkIosSimulatorArm64 --no-daemon
```

---

## test.yml — Test Pipeline

```yaml
name: Test

on:
  pull_request:
    branches: [main, develop]
  workflow_dispatch:

permissions:
  contents: read
  checks: write
  pull-requests: write

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  unit-tests:
    name: Unit Tests + Coverage
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - uses: actions/checkout@v4

      - uses: ./.github/actions/setup-android

      - name: Run common tests
        run: ./gradlew composeApp:allTests --no-daemon

      - name: Run Android unit tests
        run: ./gradlew composeApp:testDebugUnitTest --no-daemon

      - name: Generate coverage report (XML for Codecov)
        run: ./gradlew composeApp:koverXmlReportDebug --no-daemon

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v4
        with:
          files: composeApp/build/reports/kover/reportDebug.xml
          fail_ci_if_error: false

      - name: Publish test results
        if: always()
        uses: mikepenz/action-junit-report@v4
        with:
          report_paths: "**/build/test-results/**/*.xml"
          fail_on_failure: true

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: |
            composeApp/build/reports/tests/
            composeApp/build/test-results/
```

---

## release.yml — Production Release

```yaml
name: Release

on:
  push:
    tags:
      - 'v*.*.*'   # v1.2.3
  workflow_dispatch:

permissions:
  contents: write   # create GitHub Release

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false  # never cancel a release in-flight

jobs:
  release-android:
    name: Release Android (Play Store)
    runs-on: ubuntu-latest
    timeout-minutes: 45
    environment: production

    steps:
      - uses: actions/checkout@v4

      - uses: ./.github/actions/setup-android
        with:
          sign: "true"
          keystore-base64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
          keystore-password: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          key-alias: ${{ secrets.RELEASE_KEY_ALIAS }}
          key-password: ${{ secrets.RELEASE_KEY_PASSWORD }}

      - name: Build release AAB
        # Pass secrets as env vars — safer than writing to local.properties
        env:
          API_BASE_URL: ${{ secrets.API_BASE_URL }}
          GOOGLE_OAUTH_CLIENT_ID: ${{ secrets.GOOGLE_OAUTH_CLIENT_ID }}
          REVENUECAT_API_KEY: ${{ secrets.REVENUECAT_API_KEY_ANDROID }}
        run: ./gradlew composeApp:bundleRelease --no-daemon

      - name: Upload to Play Store (Internal Track)
        uses: r0adkll/upload-google-play@v1.1.3
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: com.example.app
          releaseFiles: composeApp/build/outputs/bundle/release/composeApp-release.aab
          track: internal
          status: completed
          whatsNewDirectory: .github/release-notes/

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: composeApp/build/outputs/bundle/release/composeApp-release.aab
```

---

## Gradle Signing Config

```kotlin
// composeApp/build.gradle.kts
android {
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                file("../local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            storeFile     = props.getProperty("KEYSTORE_PATH")?.let { file(it) }
            storePassword = props.getProperty("KEYSTORE_PASSWORD")
            keyAlias      = props.getProperty("KEY_ALIAS")
            keyPassword   = props.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
```

For build secrets (API keys, OAuth IDs), read from environment variables injected by CI rather than `local.properties`:

```kotlin
// composeApp/build.gradle.kts
android {
    buildConfigField("String", "API_BASE_URL",
        "\"${System.getenv("API_BASE_URL") ?: props.getProperty("API_BASE_URL", "")}\"")
}
```

---

## Gradle Optimization for CI

```kotlin
// gradle.properties  (local dev defaults)
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.incremental=true   // enabled for local dev warm builds
android.useAndroidX=true
```

```bash
# CI-specific overrides (add to Gradle commands)
--no-daemon                  # don't keep daemon alive between jobs
--build-cache                # use remote build cache if configured
-Pkotlin.incremental=false   # override gradle.properties: fresh CI build is faster without incremental
```

> `kotlin.incremental=true` is intentionally set for local dev (speeds up warm rebuilds).
> CI always overrides to `false` via `-Pkotlin.incremental=false` because runners start cold.

---

## Kover — Code Coverage

```kotlin
// composeApp/build.gradle.kts
plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.ComposableSingletons*",
                    "*_Factory",
                    "*Module_*",
                )
                annotatedBy("@Preview")
            }
        }
        verify {
            rule("Minimum coverage") {
                minBound(80)
            }
        }
    }
}
```

Key tasks:
- `koverXmlReportDebug` — generates XML for Codecov/SonarQube (use this in CI)
- `koverHtmlReportDebug` — generates HTML for human review (upload as artifact)
- `koverVerify` — enforces the `minBound` threshold

---

## nightly.yml — Instrumented Tests (Firebase Test Lab)

```yaml
name: Nightly

on:
  schedule:
    - cron: '0 2 * * *'  # 2 AM UTC daily
  workflow_dispatch:

permissions:
  contents: read

jobs:
  instrumented-tests:
    name: Firebase Test Lab
    runs-on: ubuntu-latest
    timeout-minutes: 60

    steps:
      - uses: actions/checkout@v4

      - uses: ./.github/actions/setup-android

      - name: Build test APKs
        run: ./gradlew composeApp:assembleDebug composeApp:assembleDebugAndroidTest --no-daemon

      - name: Auth with GCloud
        uses: google-github-actions/auth@v2
        with:
          credentials_json: ${{ secrets.FIREBASE_TEST_LAB_CREDENTIALS }}

      - name: Set up gcloud CLI
        uses: google-github-actions/setup-gcloud@v2

      - name: Run on Firebase Test Lab
        run: |
          gcloud firebase test android run \
            --type instrumentation \
            --app composeApp/build/outputs/apk/debug/composeApp-debug.apk \
            --test composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk \
            --device model=Pixel8,version=34,locale=en,orientation=portrait \
            --results-bucket gs://my-project-test-results \
            --results-dir=${{ github.run_id }}
```

---

## Version Bump Automation

```bash
# scripts/bump-version.sh
#!/bin/bash
# Usage: ./scripts/bump-version.sh --hotfix | --minor | --major

PROPS="versioning.properties"
VERSION_CODE=$(grep versionCode $PROPS | cut -d'=' -f2)
VERSION_NAME=$(grep versionName $PROPS | cut -d'=' -f2)

NEW_CODE=$((VERSION_CODE + 1))

case $1 in
  --hotfix) NEW_NAME=$(echo $VERSION_NAME | awk -F. '{print $1"."$2"."$3+1}') ;;
  --minor)  NEW_NAME=$(echo $VERSION_NAME | awk -F. '{print $1"."$2+1".0"}') ;;
  --major)  NEW_NAME=$(echo $VERSION_NAME | awk -F. '{print $1+1".0.0"}') ;;
  *) echo "Usage: $0 --hotfix|--minor|--major"; exit 1 ;;
esac

# Use -i without '' for Linux compatibility (CI runs on ubuntu-latest)
sed -i "s/versionCode=$VERSION_CODE/versionCode=$NEW_CODE/" $PROPS
sed -i "s/versionName=$VERSION_NAME/versionName=$NEW_NAME/" $PROPS

git add $PROPS
git commit -m "Bump version to $NEW_NAME ($NEW_CODE)"
git tag "v$NEW_NAME"
```
