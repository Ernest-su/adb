# Android ADB client

A reusable Android ADB host library extracted from [AScrcpy](https://github.com/Ernest-su/ascrcpy). It connects directly to an already enabled TCP `adbd` (usually port 5555), handles RSA authorization, executes shell commands, pushes files, and opens multiplexed ADB services. It does not provide Android 11 wireless pairing or USB transport. Minimum Android API: 26.

## Install

Add `maven { url = uri("https://jitpack.io") }` to `dependencyResolutionManagement.repositories` in `settings.gradle.kts`, then:

```kotlin
dependencies {
    implementation("com.github.Ernest-su:adb:v0.1.0")
}
```

## Use

```kotlin
val client = DefaultAdbClient.factory(applicationContext).create()
try {
    client.connect(AdbEndpoint("192.168.1.20", 5555))
    val result = client.shell("getprop ro.product.cpu.abilist")
    println(result.text())
} finally {
    client.close()
}
```

`AdbClient`, `AdbChannel`, `AdbEndpoint`, `AdbKeyProvider`, and `AdbTransport` provide the stable public boundary. The default key provider stores its RSA identity in the application's no-backup directory. The target device can require the user to authorize it on first connection. Keep remote shell commands fixed and validate user input before composing them.

## Verify and release

Run `./gradlew testDebugUnitTest publishReleasePublicationToMavenLocal --no-daemon`. Push a version tag to GitHub, then JitPack builds the AAR on demand using `jitpack.yml`. The published coordinate is `com.github.Ernest-su:adb:<tag>`.

Licensed under Apache-2.0.
