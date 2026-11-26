# SmoothRec — Grabador de pantalla para Android

Grabador de pantalla nativo desarrollado en **Kotlin** con Android SDK. Sin Compose, sin NDK, sin C++ y sin dependencias externas más allá del propio SDK y la biblioteca estándar de Kotlin.

- `applicationId`: `com.smoothrec.app`
- `minSdk 23` · `targetSdk 35` · `compileSdk 35` · JVM 17
- Interfaz XML oscura y minimalista, con botón central circular, estados de grabación y ajustes.
- Salida en MP4 dentro de `Movies/SmoothRec` mediante MediaStore.

## Repositorios y versiones

| Repositorio | Uso | Última release |
|---|---|---|
| [`buildgradlekts/SmoothRec`](https://github.com/buildgradlekts/SmoothRec) | Repositorio principal y fuente de actualizaciones OTA | **v9 · 0.5.2 · código 9** |
| [`buildgradlekts/SmoothRec-Grabador-De-Pantalla`](https://github.com/buildgradlekts/SmoothRec-Grabador-De-Pantalla) | Repositorio histórico; mantiene la v8 | v8 · 0.5.1 · código 8 |

### Descarga actual

- APK directo (v9): https://github.com/buildgradlekts/SmoothRec/releases/download/v9/app-debug.apk
- Página de la release: https://github.com/buildgradlekts/SmoothRec/releases/tag/v9
- SHA-256: `04e4a7f4490833957c6a06f77d49f9835cef889fefa03f5ec900fb4436020b00`
- Firma: certificado `Android Debug`, la misma huella SHA-256 que la v8, por lo que Android permite actualizar sin desinstalar.

Para instalar un APK descargado fuera de Play Store, Android puede pedir autorizar **“Instalar aplicaciones desconocidas”**. No desinstales la versión anterior si quieres conservar los vídeos y las preferencias.

> Nota: la v8 instalada busca actualizaciones en el repositorio histórico. Instala la v9 una vez desde el enlace anterior para que la app pase a consultar el repositorio principal.

## Actualizaciones dentro de la app (OTA)

El actualizador está en `UpdateManager.kt` y consulta por defecto:

```text
https://api.github.com/repos/buildgradlekts/SmoothRec/releases/latest
```

- **Ajustes → Comprobar actualizaciones** muestra si no hay versiones nuevas, si ya está actualizada o el número de la nueva versión.
- Solo se aceptan releases publicados: se ignoran borradores, prereleases y assets cuyo estado no sea `uploaded`.
- El tag del release (`v9`, `v10`, …) se convierte en `versionCode` y se compara con el instalado.
- La descarga usa `HttpURLConnection` con reanudación por `Range`/`If-Range`, reintentos automáticos, porcentaje y texto de bytes descargados/total.
- Antes de abrir el instalador se validan: `digest` SHA-256 de GitHub, `applicationId`, `versionCode`, `minSdk` y huella del certificado de firma.
- La instalación **nunca** es silenciosa: se confirma con el instalador oficial de Android.
- Si GitHub no responde o devuelve un error HTTP distinto de 404, se muestra un mensaje de error; 404 significa “sin releases publicados”.
- Se muestra una notificación cuando hay una versión disponible.

## Asistente de permisos (una sola vez)

El asistente visual se muestra únicamente la primera vez, o cuando se abre explícitamente desde la app:

- Explica audio, micrófono, captura de pantalla, notificaciones y optimización de batería con iconos y estado por paso.
- La aceptación se guarda de forma permanente en `SharedPreferences` (`smoothrec_setup`, clave `setup_acknowledged`).
- Ya no depende de `versionCode`, SDK, ajustes ni estado de permisos: una actualización OTA no vuelve a mostrarlo.
- Las instalaciones que aceptaron el aviso con el formato anterior se migran automáticamente.
- Si el usuario revoca un permiso desde Ajustes de Android, no reaparece el recuadro: `MainActivity` solicita **solo el permiso que falta** cuando se pulsa Grabar.
- Hay una única instancia activa del diálogo, sin duplicados entre pantallas ni componentes.

## Motor de grabación

- `ScreenCapturer`: `VirtualDisplay` con `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` alimentado por la Surface del encoder.
- `HardwareVideoEncoder`: `MediaCodec` asíncrono con entrada Surface.
- `EncoderFactory`: selecciona el codificador H.264 hardware disponible (`c2.qti.avc.encoder` en el Redmi Note 13 4G).
- `Mp4Muxer`: escribe los paquetes H.264 en MP4 sobre el descriptor de MediaStore.
- `CaptureSession`: orquesta captura → codificación → muxer → MediaStore y libera todos los recursos.
- Finalización: `signalEndOfInputStream()`, drenado, `MediaMuxer.stop()/release()`, cierre del `ParcelFileDescriptor` y publicación con `IS_PENDING=0`.
- `MediaProjection.Callback` reacciona a la revocación del consentimiento.
- Servicio en primer plano tipo `mediaProjection` con notificación persistente.

### Configuración de grabación

| Parámetro | Valor |
|---|---|
| Resolución | Pantalla / 1080P / 720P, derivada de la relación de aspecto real |
| FPS | 60 / 30 como objetivo, no garantía |
| Audio | Audio interno / Micrófono / Interno + micrófono |
| Códec | H.264 (`video/avc`) |
| Perfil / nivel | High / AVC Level 5 |
| Bitrate inicial | 8 Mbps |
| AAC | 96 kbps estéreo o 64 kbps mono |
| Contenedor | MP4 |
| Almacenamiento | `Movies/SmoothRec` vía MediaStore |
| Por defecto | Pantalla · 60 FPS · audio interno |

También están disponibles los perfiles de bitrate **AUTO / Bajo / Medio / Alto / Ultra / Máximo**, el límite de duración **Ilimitado / 5 / 15 / 30 / 60 min** y la **pausa/reanudación** desde la interfaz, la notificación y la barra flotante.

`CaptureSizeResolver` consulta `VideoCapabilities` para validar tamaño, alineación y tasa. Si la combinación elegida no es compatible, prueba la misma tasa con menor resolución y después 30 FPS, avisa al usuario y refleja los valores aplicados en la notificación. Nunca se estira, recorta ni gira la imagen.

En el Redmi Note 13 4G (1080×2400), Pantalla y 1080P convergen en **864×1920** y 720P produce **720×1600**. En pantallas 1080×2160, 1080P produce **960×1920** y 720P **720×1440**.

No se escribe metadato de rotación: el `VirtualDisplay`, el encoder y el muxer usan las mismas dimensiones. Girar el teléfono durante la grabación no cambia la orientación del MP4; hacerlo obligaría a reiniciar el encoder y podría corromper el archivo.

## Audio

- Audio interno mediante `AudioPlaybackCaptureConfiguration` sobre `MediaProjection` (API 29+).
- El formato PCM se detecta en tiempo de ejecución: se prueba estéreo a 48 kHz y se desciende a mono y/o 44,1 kHz si hace falta. Se comprueban `AudioRecord.getMinBufferSize()`, el estado real de `AudioRecord` y la existencia de un codificador AAC.
- La mezcla de interno + micrófono aplica ganancia y limitación suave para evitar saturación; ambas fuentes comparten reloj PCM y se codifican en una sola pista AAC sincronizada con el vídeo.
- Si una modalidad falla, se informa al usuario y se graba solo vídeo o la fuente disponible.
- Se han probado con sondas de API reales el codificador `c2.android.aac.encoder`, 48 kHz estéreo y el muxer AAC.

## Rendimiento

Cambios aplicados al motor sin modificar la interfaz, el audio, las preferencias ni la calidad seleccionada:

- La ruta GL con marca de agua coalesce eventos de `SurfaceTexture`: si llegan varios frames mientras se renderiza, se conserva el más reciente y se descartan los obsoletos (`staleFramesDropped`). **No se bajan los FPS configurados.**
- Se eliminó el `glClear` de pantalla completa porque el quad OES cubre todo el viewport; `eglSwapInterval(0)` evita esperas a vsync en la superficie del encoder.
- Telemetría separada y real: `captureFps` mide frames entregados por `SurfaceTexture`; `effectiveFps` mide los PTS de MediaCodec. Se registran frames obsoletos, cola máxima, edad máxima del frame, huecos de PTS, escrituras lentas y estado térmico (`PowerManager`, API 29+).
- Contadores primitivos y snapshots publicados como máximo dos veces por segundo. La ruta estable del muxer no copia `ByteBuffer`.
- `ThermalMonitor` solo informa: no reduce resolución, bitrate ni FPS automáticamente.

Los datos se escriben en `Descargas/SmoothRec-diagnostico.txt` con líneas `Telemetría:` cada 10 s y un resumen `Rendimiento:` / `Temperatura final:` al detener. También se emite el broadcast `com.smoothrec.app.action.RECORDING_STATS` con `extra_capture_fps`, `extra_dropped_frames`, `extra_pts_gap_frames`, `extra_max_write_ms` y `extra_thermal_status`. El overlay flotante muestra únicamente FPS reales del encoder.

**Limitación de medición:** con la marca de agua desactivada, `VirtualDisplay` escribe directamente en la Surface de MediaCodec y no hay un consumidor intermedio que cuente frames sin añadir una copia; en ese modo `captureFps` es `0` (no medido), nunca un valor estimado.

`DeviceCompat.kt` registra en el diagnóstico el modelo, Android/One UI, códec y FPS soportados, sin bloquear funciones por dispositivo.

## Compilación

Con el wrapper incluido:

```sh
./gradlew clean assembleDebug
```

O con el Gradle del entorno (9.9.2, AGP 8.7.3, Kotlin 2.0.21):

```sh
gradle assembleDebug
```

APK generado: `app/build/outputs/apk/debug/app-debug.apk`

## Estado de verificación

- `clean assembleDebug`: correcto.
- `test`: `NO-SOURCE` (el proyecto no tiene tests automáticos).
- `lintDebug`: 17 errores y 100 avisos preexistentes; no se añadieron nuevos errores.
- El `digest` de GitHub y el hash descargado de la v9 coinciden.
- No hay teléfono ni `adb` en este entorno: **no se ha validado en dispositivo** la instalación OTA real, la grabación simultánea de voz y audio interno en Samsung Galaxy A17 ni la estabilidad a 60 FPS en eFootball. Esas pruebas deben hacerse instalando el APK.

## Pendiente

- Control numérico libre de bitrate en kbps (por ahora se seleccionan perfiles: AUTO / Bajo / Medio / Alto / Ultra / Máximo).
- Validación en dispositivo de grabaciones largas en segundo plano con las políticas de batería del fabricante, especialmente Samsung Galaxy A17 / One UI.
- Interfaz editable para cambiar la URL del servidor OTA (el backend ya admite `setReleasesUrl`).
- Pruebas reales de OTA, audio mixto y estabilidad a 60 FPS; este entorno no tiene teléfono ni `adb`.

## Acerca de SmoothRec

- Creador y responsable del proyecto: **Keiner**.
- Asistencia de desarrollo e inteligencia artificial: **ChatGPT (OpenAI)**.
- Implementación y colaboración técnica: **OpenClaude y OpenCode**.
- TikTok: https://www.tiktok.com/@keinerxzv
- Instagram: https://www.instagram.com/keinerxzv_/
