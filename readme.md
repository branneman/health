# Health

## Stack

This is a Kotlin Multiplatform project targeting Android and Server.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [androidMain](./app/shared/src/androidMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

## Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Server: `./gradlew :server:run`

## Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Server tests: `./gradlew :server:test`

## Setting up development environment

- Install Android Studio via JetBrains Toolbox.
- Install the `Claude Code [Beta]` plugin in Android Studio.
- Add `$HOME/Library/Android/sdk/platform-tools` to your path.
- Create the following virtual device in the Android Studio Device Manager:
  ```
  Virtual Device: Pixel 6a
  API: 34 "UpsideDownCake"; Android 14.0
  Services: Android Open Source
  ```
- Add Mobile-MCP to Claude:  
  `claude mcp add mobile-mcp -- npx -y @mobilenext/mobile-mcp@latest`
- Add Superpowers to Claude:  
  `/plugin install superpowers@claude-plugins-official`
lets re