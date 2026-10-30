# gateway-mock

Host de autorización de mentira: ISO 8583 por TCP (Ktor), HSM de laboratorio, idempotencia, reversas y modos de caos.

Es parte del laboratorio de adquirencia: cinco repos hermanos que se clonan en la misma carpeta (ver `LEEME-PRIMERO.md` en la carpeta padre).

## Específico de este repo

- `Authorizer` tiene todas las reglas y no conoce Ktor. El modo caos se aplica solo en `IsoTcpServer`.
- Los modos `TIMEOUT_AFTER_APPROVAL` y `DROP_AFTER_APPROVAL` nunca afectan a reversas: si no, no habría forma de deshacer.
- Montos de prueba: centavos .51 → 51, .05 → 05, más de 10 000.00 → 61. PIN válido: 1234.

## Reglas duras del laboratorio (aplican a todos los repos)

1. **Nunca loguear ni persistir PAN completo, pista 2, PIN ni PIN block.** Solo PAN enmascarado (6 primeros + 4 últimos). Los mensajes de error tampoco llevan el valor que falló.
2. **Ninguna llave real.** La única BDK permitida es la de prueba de ANSI X9.24-1 (`0123456789ABCDEFFEDCBA9876543210`). Nada de llaves en recursos ni en `BuildConfig`.
3. **Un timeout nunca es un rechazo.** Se reintenta la trama o la reversa, **nunca el cobro**. Un estado incierto se resuelve con reversa.
4. **No inventar valores de un estándar.** Si una máscara, un tag o un código no se puede contrastar con una fuente o un vector, se deja fuera y se anota en el README como pendiente.
5. **Cada operación criptográfica usa su propia llave DUKPT.** Para firmar algo que no viene de una lectura de tarjeta (reversa, aviso), primero se avanza el KSN.
6. **El dominio no conoce el framework.** Reglas en Kotlin puro y probadas sin red, sin emulador y sin servidor; Ktor, Room y Compose quedan en los bordes.

## Convenciones

- Kotlin 2.2.10, JVM 17, Gradle 9.6 (wrapper incluido). Código, KDoc, tests y commits en español.
- Tests con nombres que se leen como una regla (`@DisplayName` en JUnit 5 o nombres con backticks en JUnit 4).
- Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `build:`, `ci:`.
- Los repos hermanos se consumen por *composite build*: si `../<repo>` existe, `settings.gradle.kts` lo incluye y se compila desde el código. Para cambiar una librería y probarla en la app no hay que publicar nada.
- Antes de cerrar un cambio: `./gradlew build` (o `./gradlew testDebugUnitTest assembleDebug` en `android-pos`) en verde.
