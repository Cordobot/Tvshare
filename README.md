# Tvshare 📡

Transmite en tiempo real lo que se ve en una **Android TV** a una **PC** (Windows/macOS/Linux) por la red local.
En la TV la captura corre como servicio en primer plano, así que sigue transmitiendo mientras usas cualquier otra app.

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin%20Multiplatform-2.4-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-Desktop-4285F4?logo=jetpackcompose&logoColor=white)
![H.264](https://img.shields.io/badge/Video-H.264%20HW-orange)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84?logo=android&logoColor=white)

---

## Tabla de contenido

- [Módulos](#módulos)
- [Cómo funciona](#cómo-funciona)
- [Stack tecnológico](#stack-tecnológico)
- [Uso](#uso)
- [Limitaciones](#limitaciones)
- [Compilar y ejecutar](#compilar-y-ejecutar)
- [Pruebas](#pruebas)

## Módulos

* [/androidApp](./androidApp) – emisor (Android TV y también teléfonos). Captura pantalla con `MediaProjection`,
  codifica H.264 por hardware y, en Android 10+, captura el audio que reproducen otras apps.
* [/desktopApp](./desktopApp) – receptor (Compose Desktop). Descubre la TV por UDP, decodifica H.264 con FFmpeg
  y reproduce el audio.
* [/shared](./shared/src/commonMain/kotlin) – protocolo común (puertos, cabeceras de paquetes, descubrimiento).

## Cómo funciona

```
Android TV (androidApp)                         PC (desktopApp)
─────────────────────────                       ─────────────────────────
ScreenCaptureService (1er plano)
 ├─ MediaProjection → VideoEncoder (H.264 HW) ┐
 ├─ AudioCapture (PCM, Android 10+)           ├─ StreamServer ══ TCP 47800 ══► StreamClient
 └─ DiscoveryResponder ◄════════════ UDP 47801 ════════════════ Discovery     ├─ H264Decoder (FFmpeg) → VideoSurface
                                                                              └─ AudioPlayer (javax.sound)

shared/Protocol.kt: puertos, cabeceras de paquetes y mensajes de descubrimiento
```

## Stack tecnológico

| Área | Tecnología |
|------|------------|
| Lenguaje | Kotlin 2.4 (Multiplatform: Android + JVM) |
| UI | Jetpack Compose (TV/teléfono) y Compose Multiplatform Desktop |
| Captura | `MediaProjection`, `AudioPlaybackCaptureConfiguration` |
| Codificación | `MediaCodec` (H.264 por hardware) |
| Decodificación (PC) | FFmpeg (presets de JavaCPP / bytedeco) |
| Audio (PC) | `javax.sound.sampled` |
| Red | Sockets TCP (stream) + UDP (descubrimiento) |
| Distribución PC | Compose Desktop: `.exe`, `.msi`, `.dmg`, `.deb` |

## Uso

1. En la TV abre **Tvshare**, elige calidad/audio y pulsa **Iniciar transmisión**; acepta “Compartir pantalla completa”.
2. Pulsa **Ir al inicio y seguir transmitiendo** y abre tu película o app.
3. En la PC abre Tvshare: la TV aparece en la lista (o conecta con la IP que muestra la TV). Doble clic / F11 = pantalla completa.

Puertos: TCP 47800 (stream) y UDP 47801 (descubrimiento). La primera vez Windows pedirá permiso de firewall para Java/Tvshare.

## Limitaciones

* Apps con DRM (Netflix, Disney+, Prime Video…) marcan su video como seguro: se verá negro y suelen bloquear la captura de audio.
  Funciona con contenido sin DRM (YouTube en muchos casos, IPTV, reproductores locales, juegos, menús).
* El audio requiere Android 10+. En Android 14+ hay que aceptar el permiso de captura cada vez que se inicia.

## Compilar y ejecutar

Requisitos: Android Studio reciente (AGP 9), JDK 17+, Android SDK 37.

- TV: `./gradlew :androidApp:assembleDebug` (instalar con `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`)
- PC: `./gradlew :desktopApp:run` · instalador: `./gradlew :desktopApp:packageMsi`

## Pruebas

```bash
./gradlew :shared:jvmTest :shared:testAndroidHostTest :androidApp:testDebugUnitTest
```

---

Desarrollado por **Adrián Alvarez** ([@Cordobot](https://github.com/Cordobot)).
