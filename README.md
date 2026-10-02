# NegativeMusic

Reproductor Android de música local con interfaz moderna inspirada en servicios de streaming. Lee archivos de audio indexados por Android; no incorpora un catálogo en línea ni sube canciones a servidores.

## Stack
- Kotlin, Jetpack Compose, Material 3
- Android 8.0+ (API 26), JDK 17
- MediaStore para descubrir audio local
- Media3 ExoPlayer y MediaSessionService para reproducción en segundo plano
- GitHub Actions para generar el APK de depuración

## Incluye
Biblioteca local, búsqueda, favoritos, creación/edición de playlists y portada elegida, reproducción, cola, aleatorio, repetición, temporizador, tema claro/oscuro/sistema, controles de volumen y pantalla de almacenamiento.

## Compilación
Cada push a main ejecuta el workflow Android APK. Abre Actions, entra en la ejecución y descarga el artefacto NegativeMusic-debug-apk. Es un APK de pruebas; Google Play requiere versión de publicación firmada y validación adicional.

## Estado de audio avanzado
Los controles iniciales de crossfade y audio mono están identificados como preferencias en desarrollo; la mezcla cruzada y el procesamiento mono requieren integración adicional en el motor de audio. El volumen es un control de salida de la aplicación, no un cambio del volumen maestro del dispositivo.
