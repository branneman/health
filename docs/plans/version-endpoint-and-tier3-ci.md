# Version Endpoint & Tier 3 CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `GET /version` endpoint returning the deployed git SHA, bake the SHA into the Docker image at build time, and add a Tier 3 API test job to the deploy pipeline that polls the version endpoint until Watchtower has deployed the new image, then runs the full API test suite against production.

**Architecture:** The git SHA is passed as a Docker build arg (`GIT_SHA`) and persisted as an `ENV` var in the runtime stage so the JVM can read it via `System.getenv`. The server exposes it at `GET /version`. After the `build-push` job pushes the new image, a downstream `api-tests` job polls `/version` every 30 s (up to 10 min) until the SHA matches, then runs `./gradlew :server:apiTest` against the live server. API tests can also be run locally at any time by pointing `API_TEST_SERVER_URL` at the VPS.

**Tech Stack:** Ktor + kotlinx.serialization (server), Docker build args (Dockerfile), GitHub Actions with `curl` + `jq` polling (CI).

---

### Task 1: Add `GET /version` endpoint

**Files:**
- Modify: `server/src/main/kotlin/org/branneman/health/Application.kt`

- [ ] Add a `VersionResponse` data class at the bottom of `Application.kt`, alongside the other server-local data classes. It needs `@Serializable` so Ktor's content negotiation can serialize it to JSON.

```kotlin
import kotlinx.serialization.Serializable  // add to imports at top of file

@Serializable
data class VersionResponse(val sha: String)
```

- [ ] Add the `/version` route inside the `routing { }` block in `fun Application.module(dataSource: javax.sql.DataSource)`, after the existing `get("/server-health")` route (around line 108):

```kotlin
get("/version") {
    call.respond(VersionResponse(System.getenv("GIT_SHA") ?: "unknown"))
}
```

- [ ] Verify compilation: `./gradlew :server:compileKotlin`

Expected output: `BUILD SUCCESSFUL`

- [ ] Commit:

```bash
git add server/src/main/kotlin/org/branneman/health/Application.kt
git commit -m "feat(server): add GET /version endpoint returning deployed git SHA"
```

---

### Task 2: Write VersionApiTest

**Files:**
- Create: `server/src/apiTest/kotlin/org/branneman/health/VersionApiTest.kt`

The `apiTest` source set has `compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath` (see `server/build.gradle.kts`), so `VersionResponse` defined in Task 1 is directly importable here.

- [ ] Create the test file:

```kotlin
package org.branneman.health

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionApiTest : ApiTestBase() {

    @Test
    fun `version endpoint returns 200 with non-blank sha`() = runTest {
        val response = client.get("$serverUrl/version")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<VersionResponse>()
        assertTrue(body.sha.isNotBlank())
    }
}
```

- [ ] Verify compilation: `./gradlew :server:compileApiTestKotlin`

Expected output: `BUILD SUCCESSFUL`

- [ ] Commit:

```bash
git add server/src/apiTest/kotlin/org/branneman/health/VersionApiTest.kt
git commit -m "test(server): add VersionApiTest for GET /version endpoint"
```

---

### Task 3: Bake GIT_SHA into Docker image

**Files:**
- Modify: `server/Dockerfile`

The `ARG` instruction must be declared in the stage that uses it (the runtime stage, after the second `FROM`). `ENV` converts it from a build-time arg into a persistent environment variable that the JVM process reads at runtime. The default value `unknown` means `docker build` without `--build-arg` still works locally.

- [ ] Replace the entire Dockerfile with:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY . .
RUN echo "server.baseUrl=https://placeholder" >> local.properties
RUN ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
ARG GIT_SHA=unknown
ENV GIT_SHA=$GIT_SHA
WORKDIR /app
COPY --from=build /build/server/build/install/server .
CMD ["bin/server"]
```

- [ ] Commit:

```bash
git add server/Dockerfile
git commit -m "chore: bake GIT_SHA into Docker runtime image via build arg"
```

---

### Task 4: Update deploy.yml — build arg + Tier 3 job

**Files:**
- Modify: `.github/workflows/deploy.yml`

Two changes:
1. Pass `build-args: GIT_SHA=${{ github.sha }}` to `docker/build-push-action`.
2. Add an `api-tests` job that `needs: build-push`, polls `/version` until the SHA matches, then runs `./gradlew :server:apiTest`.

The polling loop tries 20 times × 30 s = up to 10 minutes. Watchtower typically reacts within 1–2 minutes. The loop exits early on match; if it times out, the job fails rather than running tests against the wrong version.

- [ ] Replace the entire deploy.yml with:

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
          build-args: GIT_SHA=${{ github.sha }}
          tags: |
            ghcr.io/branneman/health-server:latest
            ghcr.io/branneman/health-server:${{ github.sha }}

  api-tests:
    needs: build-push
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin

      - uses: gradle/actions/setup-gradle@v4

      - name: Wait for Watchtower to deploy
        env:
          SERVER_URL: ${{ secrets.API_TEST_SERVER_URL }}
        run: |
          EXPECTED="${{ github.sha }}"
          DEPLOYED=""
          for i in $(seq 1 20); do
            DEPLOYED=$(curl -sf "$SERVER_URL/version" | jq -r .sha 2>/dev/null) || DEPLOYED=""
            [ "$DEPLOYED" = "$EXPECTED" ] && echo "Server updated to $EXPECTED" && break
            echo "Attempt $i/20: deployed='$DEPLOYED', waiting 30s..."
            sleep 30
          done
          [ "$DEPLOYED" = "$EXPECTED" ] || { echo "Timed out waiting for Watchtower"; exit 1; }

      - name: Run API tests
        env:
          API_TEST_SERVER_URL: ${{ secrets.API_TEST_SERVER_URL }}
          API_TEST_EMAIL: ${{ secrets.API_TEST_EMAIL }}
          API_TEST_PASSWORD: ${{ secrets.API_TEST_PASSWORD }}
        run: ./gradlew :server:apiTest
```

- [ ] Commit:

```bash
git add .github/workflows/deploy.yml
git commit -m "ci: pass GIT_SHA build arg and add Tier 3 api-tests pipeline job"
```

---

## After merging

Add three repository secrets in **GitHub → Settings → Secrets and variables → Actions → New repository secret**:

| Name | Value |
|------|-------|
| `API_TEST_SERVER_URL` | e.g. `https://health.bran.name` |
| `API_TEST_EMAIL` | `test+api@bran.name` |
| `API_TEST_PASSWORD` | the test account password |

These are already read by `ApiTestBase` when running `./gradlew :server:apiTest` locally via `.env`. The pipeline reads them as secrets instead.
