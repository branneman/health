# Auto Update

Self-hosted, in-app APK update mechanism. No Firebase, no Play Store. GitHub Actions
builds and publishes the APK to GitHub Releases; the server enforces that every client
runs the exact current version; the app detects a mismatch on launch, downloads
silently, and installs via the system sheet.

---

## Goals

- Every deploy automatically makes the new APK available before the server goes live.
- Server denies requests from any client not running the exact current version.
- Update flow is low-friction: one tap on a dialog + one tap on the system install
  sheet. Total active attention: ~5 seconds.
- No manual version number management anywhere.

---

## Versioning scheme

Both the app and the server derive the same version string from git at build time:

```
{commit-count}-{short-hash}    e.g.  32-2f765b5
```

```bash
COUNT=$(git rev-list --count HEAD)
HASH=$(git rev-parse --short HEAD)
VERSION="${COUNT}-${HASH}"
```

**Two components, two purposes:**
- `COUNT` — human-readable sequence number; indicates relative ordering.
- `HASH` — commit identity; the actual enforcement key. Collision-resistant under
  history rewrites (rebase, squash, amend on main).

**Android build:** `versionCode` (integer, required by Android) = `COUNT`.
`versionName` (display string) = the full `COUNT-HASH` composite. Derived in
`app/build.gradle.kts`:

```kotlin
fun String.execute() = ProcessBuilder(split(" "))
    .redirectErrorStream(true).start().inputStream.bufferedReader().readText()

val gitCount = "git rev-list --count HEAD".execute().trim().toInt()
val gitHash  = "git rev-parse --short HEAD".execute().trim()

android {
    defaultConfig {
        versionCode = gitCount
        versionName = "$gitCount-$gitHash"
    }
}
```

**Server build:** no `BuildConfig` equivalent exists in JVM/Ktor. The version string is
written into a resource file at build time via Gradle's `processResources`:

`server/src/main/resources/version.properties` (template, committed):
```
app.version=${appVersion}
```

`server/build.gradle.kts`:
```kotlin
val gitCount = "git rev-list --count HEAD".execute().trim()
val gitHash  = "git rev-parse --short HEAD".execute().trim()

tasks.processResources {
    filesMatching("version.properties") {
        expand("appVersion" to "$gitCount-$gitHash")
    }
}
```

At runtime the server reads this once on startup:

```kotlin
val currentVersion: String = Properties().apply {
    load(object {}.javaClass.getResourceAsStream("/version.properties"))
}.getProperty("app.version")
```

---

## Server enforcement

Every API request must carry an `X-App-Version` header. The server compares it against
`currentVersion` using **string equality** on the full composite. A mismatch returns
`426 Upgrade Required` with no body. No partial matching, no `>=` comparison — the
hash ensures uniqueness even if commit counts collide after history rewrites.

```kotlin
intercept(ApplicationCallPipeline.Plugins) {
    val clientVersion = call.request.headers["X-App-Version"]
    if (call.request.path() != "/api/update" && clientVersion != currentVersion) {
        call.respond(HttpStatusCode(426, "Upgrade Required"))
        finish()
    }
}
```

The `/api/update` endpoint is explicitly excluded — a client must be able to learn
about and download the update even if it is already blocked.

---

## Update check endpoint

`GET /api/update` — unauthenticated, public.

Response:

```json
{
  "versionCode": 32,
  "versionName": "32-2f765b5",
  "apkUrl": "https://github.com/{owner}/{repo}/releases/download/32-2f765b5/app-release.apk",
  "releaseNotes": "Fixed calorie widget not refreshing"
}
```

The `apkUrl` is the GitHub Releases download URL. Because the URL is fully predictable
from the version string, the server can construct it at startup from `currentVersion`
— no database or config file needed.

---

## APK hosting — GitHub Releases

The APK is hosted on GitHub Releases, tagged with the version string (e.g.
`32-2f765b5`). The APK is public; the repo is open source and contains no secrets.

Download URL pattern:
```
https://github.com/{owner}/{repo}/releases/download/{version}/app-release.apk
```

---

## CI/CD pipeline

The GitHub Actions workflow has two jobs with an explicit dependency. The dependency
makes the race condition — "server demands new version but APK not yet available" —
structurally impossible.

```yaml
jobs:
  publish-apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0          # needed for git rev-list --count
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - name: Build release APK
        run: ./gradlew :app:assembleRelease
      - name: Compute version
        id: version
        run: |
          COUNT=$(git rev-list --count HEAD)
          HASH=$(git rev-parse --short HEAD)
          echo "name=${COUNT}-${HASH}" >> $GITHUB_OUTPUT
      - name: Create GitHub Release and upload APK
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ steps.version.outputs.name }}
          files: app/build/outputs/apk/release/app-release.apk

  deploy-server:
    needs: publish-apk            # server goes live only after APK is published
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - name: Build server JAR
        run: ./gradlew :server:buildFatJar
      - name: Deploy to VPS
        run: |
          scp server/build/libs/server-all.jar hetzner:/opt/health/server.jar
          ssh hetzner "systemctl restart health-server"
```

APK signing (release keystore) is handled in the `publish-apk` job via GitHub Actions
secrets before `assembleRelease` is called. The exact signing configuration is a
deployment concern, not specified here.

---

## Android: update state machine

The app holds a single top-level `UpdateState` that gates all server communication:

```kotlin
enum class UpdateState { IDLE, DOWNLOADING, BLOCKED }
```

| State | Server calls | UI |
|---|---|---|
| `IDLE` | Normal | Normal |
| `DOWNLOADING` | Suppressed (offline-only) | Persistent banner: "Downloading update…" |
| `BLOCKED` | Suppressed | Full-screen block: only "Update" button visible |

Transitions:
- `IDLE → DOWNLOADING`: user taps "Update" in the update dialog
- `IDLE → BLOCKED`: user taps "Later" in the update dialog
- `IDLE → BLOCKED`: any server call returns `426` (safety net — fires if update check
  was skipped or failed but server is already enforcing the new version)
- `DOWNLOADING → BLOCKED`: download fails (DownloadManager reports failure)
- Any state → app relaunches after install: state resets to `IDLE`

The `BLOCKED` screen has a single "Update" button that re-enters the download flow
(transitions to `DOWNLOADING`).

All repositories check `UpdateState` before making network calls. When state is
`DOWNLOADING` or `BLOCKED`, they return cached/local data or skip the call.

---

## Android: update check on launch

Fired as a non-blocking side-effect after the home screen has rendered. A network
failure results in a silent skip — the user is not interrupted, and the check will
run again next launch.

```kotlin
LaunchedEffect(Unit) {
    val info = updateRepository.checkForUpdate() ?: return@LaunchedEffect
    if (info.versionCode > BuildConfig.VERSION_CODE) {
        showUpdateDialog(info)  // sets local dialog state; does not block
    }
}
```

The `X-App-Version` header is attached to every Ktor client request via an interceptor
so it applies to the update check and all other calls without repetition:

```kotlin
httpClient.plugin(HttpSend).intercept { request ->
    request.header("X-App-Version", BuildConfig.VERSION_NAME)
    execute(request)
}
```

---

## Android: download

Uses `DownloadManager` — not OkHttp/Ktor. Handles pause/resume, shows progress in the
notification shade, and survives the app going to the background.

Before enqueueing, delete any stale `update.apk` from a previous failed attempt:

```kotlin
fun startDownload(context: Context, apkUrl: String): Long {
    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.resolve("update.apk")
        ?.takeIf { it.exists() }
        ?.delete()

    val request = DownloadManager.Request(Uri.parse(apkUrl))
        .setTitle("App update")
        .setDescription("Downloading…")
        .setDestinationInExternalFilesDir(
            context, Environment.DIRECTORY_DOWNLOADS, "update.apk"
        )
        .setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
    return context.getSystemService(DownloadManager::class.java).enqueue(request)
}
```

---

## Android: install

A `BroadcastReceiver` registered for `ACTION_DOWNLOAD_COMPLETE` handles both success
and failure:

```kotlin
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val dm = context.getSystemService(DownloadManager::class.java)
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))

        if (cursor.moveToFirst()) {
            val status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            )
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                launchInstall(context)
            } else {
                // download failed — transition to BLOCKED so user can retry
                UpdateStateHolder.set(UpdateState.BLOCKED)
            }
        }
        cursor.close()
    }

    private fun launchInstall(context: Context) {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk"
        )
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
        // update.apk is cleaned up at the start of the next download attempt,
        // not here — startActivity returns before the installer has read the file
    }
}
```

Since Android 7 (API 24, our `minSdk`), raw `file://` URIs are rejected. `FileProvider`
is mandatory. Required additions:

`app/src/main/res/xml/file_paths.xml`:
```xml
<paths>
    <external-files-path name="downloads" path="Download/" />
</paths>
```

`AndroidManifest.xml` provider entry:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

---

## Android: permissions

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

---

## Error handling

| Scenario | Behaviour |
|---|---|
| Update check — network unreachable | Silent skip. No dialog. Retry next launch. |
| Update check — server returns error | Silent skip. No dialog. Retry next launch. |
| Download fails | DownloadManager shows failure in notification shade. `UpdateState → BLOCKED`. User taps "Update" to retry. Stale partial file is deleted before the next attempt. |
| `426` received during normal use | `UpdateState → BLOCKED`. Same block screen as "Later". |

---

## First-run experience

The first time the install intent fires, Android may redirect the user to **Settings →
"Install unknown apps"** to grant permission for this app to install APKs. This is a
one-time system prompt Android handles automatically — it is not something the app can
pre-grant. After granting, the user returns to the install sheet and taps "Install."

Total first-run friction: enable unknown apps (one-time) + one tap on install sheet.
All subsequent updates: one tap on the dialog + one tap on the install sheet.

---

## Scope

**In scope:**
- `GET /api/update` Ktor route
- Server-wide `X-App-Version` enforcement interceptor (426 on mismatch)
- Version string derived from git at build time in both app and server
- GitHub Actions workflow: `publish-apk` → `deploy-server` (ordered)
- Android `UpdateState` machine, offline suppression during download
- `DownloadManager` download + `BroadcastReceiver` + `FileProvider` install
- Manifest permissions, `file_paths.xml`, provider declaration
- Stale APK cleanup (deleted at the start of each new download attempt)

**Out of scope:**
- Delta/patch APKs
- Background periodic update checks between launches
- Rollback / forced downgrade
- Signed APK verification beyond Android's own installer enforcement
- Multiple simultaneous users on different versions (single-user app)
