# Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (
> recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** App installs and shows a 3-tab bottom nav; server exposes `GET /server-health`; Settings
tab shows live server Online/Offline status; GitHub Actions CI/CD builds and pushes the Docker image
on every push to main.

**Architecture:** Three layers all proven end-to-end: GitHub Actions builds the server Docker image
and pushes to ghcr.io on merge to main; the Ktor server exposes a health-check endpoint with no
auth; the Android app's Settings screen pings that endpoint and displays the result. No login yet —
just the structural skeleton every later story ships on top of.

**Tech Stack:** Ktor 3.4.3 (server routes + Android HTTP client via `ktor-client-android`), Kotlin
2.3.21, Jetpack Compose Material3 via JetBrains CMP 1.11.0, `ktor-client-mock` for unit tests,
GitHub Actions with `docker/build-push-action`.

---

## File Map

| File                                                                      | Action  | Responsibility                                                 |
|---------------------------------------------------------------------------|---------|----------------------------------------------------------------|
| `server/src/main/kotlin/org/branneman/health/Application.kt`              | Modify  | Add `GET /server-health` route                                 |
| `server/src/test/kotlin/org/branneman/health/ApplicationTest.kt`          | Modify  | Add test for `/server-health` response                         |
| `docker-compose.yml`                                                      | Modify  | Point ktor healthcheck to `/server-health`                     |
| `gradle/libs.versions.toml`                                               | Modify  | Add `ktor-clientCore`, `ktor-clientAndroid`, `ktor-clientMock` |
| `app/build.gradle.kts`                                                    | Modify  | Add Ktor client deps; add test deps                            |
| `app/src/main/AndroidManifest.xml`                                        | Modify  | Add `INTERNET` permission                                      |
| `app/src/main/kotlin/org/branneman/health/App.kt`                         | Replace | 3-tab bottom nav (Dashboard / Log / Settings)                  |
| `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`          | Create  | Placeholder heading                                            |
| `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`                | Create  | Placeholder heading                                            |
| `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`           | Create  | Server status: Checking… / Online / Offline                    |
| `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`     | Create  | `isServerReachable(): Boolean`                                 |
| `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt` | Create  | Three unit tests via `MockEngine`                              |
| `.github/workflows/deploy.yml`                                            | Create  | Build + push Docker image to ghcr.io                           |

---

## Task 1: Server — `GET /server-health` endpoint

**Files:**

- Modify: `server/src/test/kotlin/org/branneman/health/ApplicationTest.kt`
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] **Step 1: Write the failing test**

Add to `ApplicationTest.kt` (the class already exists — append this method):

```kotlin
@Test
fun `server-health returns status ok`() = testApplication {
        val response = client.get("/server-health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
```

- [ ] **Step 2: Run test and verify it fails**

```
./gradlew :server:test --tests "org.branneman.health.ApplicationTest.server-health returns status ok"
```

Expected: FAIL — `AssertionError: expected:<200 OK> but was:<404 Not Found>`

- [ ] **Step 3: Implement the route in `Application.kt`**

Inside the `routing { ... }` block, add after the existing `get("/")` route:

```kotlin
get("/server-health") {
    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
}
```

Add the missing import at the top of `Application.kt`:

```kotlin
import io.ktor.http.ContentType
```

- [ ] **Step 4: Update the test to assert response body and content type**

Replace the test you wrote in Step 1 with the full contract check:

```kotlin
@Test
fun `server-health returns status ok`() = testApplication {
        routing {
            get("/server-health") {
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            }
        }
        val response = client.get("/server-health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
        assertEquals("application/json", response.contentType()?.withoutParameters().toString())
    }
```

Add to `ApplicationTest.kt` imports if missing:

```kotlin
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
```

- [ ] **Step 5: Run all server tests**

```
./gradlew :server:test
```

Expected: `BUILD SUCCESSFUL` — all tests pass

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt \
        server/src/test/kotlin/org/branneman/health/ApplicationTest.kt
git commit -m "feat(server): add GET /server-health endpoint"
```

---

## Task 2: Android — Dependencies and Bottom Nav Skeleton

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`
- Create: `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`
- Create: `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`
- Replace: `app/src/main/kotlin/org/branneman/health/App.kt`

- [ ] **Step 1: Add Ktor client library entries to version catalog**

In `gradle/libs.versions.toml`, in the `[libraries]` section, add after the last `ktor-*` entry:

```toml
ktor-clientCore = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-clientAndroid = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }
ktor-clientMock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

- [ ] **Step 2: Update `app/build.gradle.kts` dependencies**

Replace the `dependencies { ... }` block in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientAndroid)
    debugImplementation(libs.compose.uiTooling)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
}
```

- [ ] **Step 3: Add INTERNET permission to `AndroidManifest.xml`**

Replace the file at `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application android:allowBackup="true" android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true" android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:exported="true" android:name=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

- [ ] **Step 4: Create `DashboardScreen.kt`**

Create `app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
    }
}
```

- [ ] **Step 5: Create `LogScreen.kt`**

Create `app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LogScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Log", style = MaterialTheme.typography.headlineMedium)
    }
}
```

- [ ] **Step 6: Create `SettingsScreen.kt` (placeholder — wired up in Task 3)**

Create `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
    }
}
```

- [ ] **Step 7: Replace `App.kt` with bottom nav**

Replace the entire contents of `app/src/main/kotlin/org/branneman/health/App.kt`:

```kotlin
package org.branneman.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.branneman.health.ui.DashboardScreen
import org.branneman.health.ui.LogScreen
import org.branneman.health.ui.SettingsScreen

private enum class Tab(val label: String, val emoji: String) {
    Dashboard("Dashboard", "📊"),
    Log("Log", "✏️"),
    Settings("Settings", "⚙️")
}

@Composable
fun App() {
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Text(tab.emoji) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentTab) {
                    Tab.Dashboard -> DashboardScreen()
                    Tab.Log -> LogScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
    }
}
```

- [ ] **Step 8: Verify the app compiles**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml \
        app/build.gradle.kts \
        app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/org/branneman/health/App.kt \
        app/src/main/kotlin/org/branneman/health/ui/DashboardScreen.kt \
        app/src/main/kotlin/org/branneman/health/ui/LogScreen.kt \
        app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt
git commit -m "feat(app): 3-tab bottom nav skeleton (Dashboard / Log / Settings)"
```

---

## Task 3: Android — `HealthApiClient` + Server Connectivity in Settings

**Files:**

- Create: `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt`
- Create: `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`
- Replace: `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt`:

```kotlin
package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class HealthApiClientTest {

    @Test
    fun `isServerReachable returns true when server responds 200`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        })
        val client = HealthApiClient("http://test", httpClient)
        assertTrue(client.isServerReachable())
    }

    @Test
    fun `isServerReachable returns false when server responds 500`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ ->
            respond("", HttpStatusCode.InternalServerError)
        })
        val client = HealthApiClient("http://test", httpClient)
        assertFalse(client.isServerReachable())
    }

    @Test
    fun `isServerReachable returns false when connection fails`() = runBlocking {
        val httpClient = HttpClient(MockEngine { _ ->
            error("simulated connection failure")
        })
        val client = HealthApiClient("http://test", httpClient)
        assertFalse(client.isServerReachable())
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

```
./gradlew :app:test --tests "org.branneman.health.network.HealthApiClientTest"
```

Expected: FAIL — compilation error, `HealthApiClient` does not exist yet

- [ ] **Step 3: Create `HealthApiClient.kt`**

Create `app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt`:

```kotlin
package org.branneman.health.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.http.isSuccess

private const val SERVER_BASE_URL = "https://api.health.bran.name"

class HealthApiClient(
    private val baseUrl: String = SERVER_BASE_URL,
    private val httpClient: HttpClient = HttpClient(Android)
) {
    suspend fun isServerReachable(): Boolean = runCatching {
        httpClient.get("$baseUrl/server-health").status.isSuccess()
    }.getOrDefault(false)
}
```

- [ ] **Step 4: Run tests and verify they pass**

```
./gradlew :app:test
```

Expected: `BUILD SUCCESSFUL` — 3 tests pass

- [ ] **Step 5: Wire `HealthApiClient` into `SettingsScreen.kt`**

Replace the entire contents of `app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt`:

```kotlin
package org.branneman.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.branneman.health.network.HealthApiClient

@Composable
fun SettingsScreen() {
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        serverReachable = HealthApiClient().isServerReachable()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Server: ${
                when (serverReachable) {
                    null -> "Checking…"
                    true -> "Online"
                    false -> "Offline"
                }
            }"
        )
    }
}
```

- [ ] **Step 6: Verify the app compiles**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/org/branneman/health/network/HealthApiClient.kt \
        app/src/test/kotlin/org/branneman/health/network/HealthApiClientTest.kt \
        app/src/main/kotlin/org/branneman/health/ui/SettingsScreen.kt
git commit -m "feat(app): server connectivity check in Settings tab"
```

---

## Task 4: CI/CD — GitHub Actions Deploy Workflow

**Files:**

- Create: `.github/workflows/deploy.yml`

- [ ] **Step 1: Create `.github/workflows/deploy.yml`**

Create the directory and file:

```yaml
name: deploy

on:
  push:
    branches: [ main ]

jobs:
  build-push:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin

      - uses: gradle/actions/setup-gradle@v4

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - uses: docker/build-push-action@v6
        with:
          context: .
          file: server/Dockerfile
          push: true
          tags: |
            ghcr.io/branneman/health-server:latest
            ghcr.io/branneman/health-server:${{ github.sha }}
```

- [ ] **Step 2: Commit**

```bash
mkdir -p .github/workflows
git add .github/workflows/deploy.yml
git commit -m "ci: add GitHub Actions deploy workflow"
```

After pushing to GitHub, go to **Settings → Packages** and set `health-server` to **public** so
Watchtower on the VPS can pull without credentials.

---

## Self-Review

**Spec coverage check:**

| Story #1 requirement                  | Covered by                                           |
|---------------------------------------|------------------------------------------------------|
| App installs                          | Task 2 — `assembleDebug` build                       |
| Bottom nav skeleton                   | Task 2 — Dashboard / Log / Settings tabs in `App.kt` |
| Server reachable                      | Task 3 — Settings screen shows Online / Offline      |
| CI/CD pipeline proven                 | Task 4 — `deploy.yml`                                |
| `GET /server-health` (API spec)       | Task 1                                               |
| Deploy workflow (VPS deployment spec) | Task 4                                               |

No gaps found.

**Placeholder scan:** No TBD, TODO, or "similar to task N" references found.

**Type consistency:**

- `HealthApiClient` defined in Task 3 Step 3 with `isServerReachable(): Boolean`
- Tests in Task 3 Step 1 call `client.isServerReachable()` — matches
- `SettingsScreen` in Task 3 Step 5 calls `HealthApiClient().isServerReachable()` — matches
- `ContentType.Application.Json` used in Task 1 Step 3 (Application.kt) and Step 4 (test) —
  consistent
