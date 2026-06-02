# Health

This app helps you see whether you're on track to stay healthy (lose weight + gain strength/stamina)
by bringing together the two sides of the equation in one place: the energy you burn each day and
the food you eat. It pulls your daily calorie expenditure automatically from your smartwatch and
pairs it with quick food logging — designed so your regular meals take a single tap to record — then
turns that into a clear picture of how your day, and your trend over time, is shaping up. An android
homescreen widget gives you an at-a-glance read on whether you're on track plus a useful insight,
while the full app shows the history and detail behind it. It works offline, so logging never has to
wait for a connection.

## Stack

Kotlin everywhere — Android app, shared DTOs, and backend server in one monorepo.

* [`/app`](./app/src/main/kotlin) — Android app (Jetpack Compose)
* [`/shared`](./shared/src/commonMain/kotlin) — KMP module: API DTOs shared between app and server
* [`/server`](./server/src/main/kotlin) — Ktor server (JVM), backed by PostgreSQL

## Running the apps

Use the run configurations provided by the run widget in your IDE’s toolbar. You can also use these
commands and options:

- Android app: `./gradlew :app:assembleDebug`
- Server: `set -a; source .env; set +a; ./gradlew :server:run`

## Running tests

Use the run button in your IDE’s editor gutter, or run tests using Gradle tasks:

- Server tests: `./gradlew :server:test`

## Setting up development environment

- Install Docker + Docker Compose:  
  `brew install colima docker docker-compose && colima start`
- Run PostgreSQL via Docker Compose: `docker compose up -d`
- Install Android Studio via JetBrains Toolbox.
- Install the `Claude Code [Beta]` plugin in Android Studio.
- Add `$HOME/Library/Android/sdk/platform-tools` to your path.
- Create the following virtual device in the Android Studio Device Manager:
  ```
  Virtual Device: Pixel 6a
  API: 34 "UpsideDownCake"; Android 14.0
  Services: Android Open Source
  ```
- Connect Postgres MCP server to Claude:  
  `claude mcp add --transport sse postgres http://localhost:8001/sse`
- Add [Mobile-MCP](https://github.com/mobile-next/mobile-mcp#readme) to Claude:  
  `claude mcp add mobile-mcp -- npx -y @mobilenext/mobile-mcp@latest`
- Add [Superpowers](https://github.com/obra/superpowers#readme) to Claude:  
  `claude plugin install superpowers@claude-plugins-official`

## Re-applying schema changes

`docker compose down -v && docker compose up -d`
