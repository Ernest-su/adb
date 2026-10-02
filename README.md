# Android ADB client

A reusable Android ADB host library extracted from [AScrcpy](https://github.com/Ernest-su/ascrcpy). It supports legacy TCP ADB, Android 11+ Wireless debugging pairing and TLS connections, and Android USB Host connections. It handles RSA authorization, shell commands, file pushes, and multiplexed ADB services. Minimum Android API: 26.

This AGP 9.0.1 project has two modules: `:adb` contains the reusable AAR, and `:app` is an installable demo that depends on `:adb`.

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

The demo app is a Compose-based target device manager. After a TCP, Wireless debugging,
or USB connection it can show device properties, installed application names and versions,
browse remote files with type/size/modified metadata, and download files or folders through
the ADB sync `RECV` (`pull`) service without installing anything on the target. Downloads use
Android's system folder picker.

Connection, authentication, shell, push, and pull diagnostics are emitted under the
`AdbClient` logcat tag. The demo adds higher-level failures under `AdbManager` and
`AdbDeviceManager`.

### Android 11+ Wireless debugging

Enable **Wireless debugging** on the target device. In **Pair device with pairing code**, note the temporary pairing address/port and six-digit code. Pair once, then use the **separate connection port** shown on the main Wireless debugging screen. These ports may change when the setting is toggled. The library accepts explicit addresses and ports; the demo app additionally discovers services with Android NSD for QR pairing.

```kotlin
val client = DefaultAdbClient.factory(applicationContext).create()
try {
    client.pairWireless(AdbEndpoint("192.168.1.20", 37123), "123456")
    client.connectWireless(AdbEndpoint("192.168.1.20", 39999))
    println(client.shell("getprop ro.product.model").text())
} finally {
    client.close()
}
```

The same persistent RSA identity is used for TCP, USB, pairing, and wireless TLS. Wireless pairing uses direct protocol code in this library plus general-purpose Conscrypt, Bouncy Castle, and SPAKE2 crypto dependencies; it does not depend on another ADB library.

### USB Host

The device running the app must support USB Host mode. Enable USB debugging on the target device, connect it by USB, and approve both the Android USB permission dialog on the host and the RSA debugging authorization on the target. Obtain `UsbManager` permission with `requestPermission()` before calling `connectUsb()`; the demo shows this flow.

```kotlin
val manager = getSystemService(UsbManager::class.java)
val device = UsbAdbTransport.discover(manager).first()
check(manager.hasPermission(device)) // Request permission in the app UI if needed.
val client = DefaultAdbClient.factory(applicationContext).create()
try {
    client.connectUsb(device)
    println(client.shell("getprop ro.product.model").text())
} finally {
    client.close()
}
```

## Demo

Run `./gradlew :app:assembleDebug --no-daemon` and install `app/build/outputs/apk/debug/app-debug.apk` on an Android device. Select TCP ADB, wireless debugging with a pairing code, wireless debugging with a QR code, or USB Host to see the inputs for that method. Pairing and connection addresses are separate in pairing-code mode. For QR pairing, tap **Show pairing QR code** in the demo, then scan the displayed code from **Wireless debugging → Pair device with QR code** on another Android device on the same Wi-Fi network. The demo discovers the temporary pairing service, pairs, and connects to the wireless ADB service automatically. The interface and activity log follow the device language in English or Chinese. The **Read device model** button runs `getprop ro.product.model` through the library and displays its output.

## Verify and release

Run `./gradlew :adb:testDebugUnitTest :app:assembleDebug :adb:publishReleasePublicationToMavenLocal --no-daemon`. Push a version tag to GitHub, then JitPack builds the AAR on demand using `jitpack.yml`. The published coordinate is `com.github.Ernest-su:adb:<tag>`.

Licensed under Apache-2.0.
