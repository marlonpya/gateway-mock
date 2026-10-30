# gateway-mock

Host de autorización de laboratorio: recibe **ISO 8583 por TCP**, verifica el
**MAC** y el **PIN** con la BDK como lo haría el HSM del adquirente, guarda
una bitácora con **idempotencia**, procesa **reversas** y **avisos offline**, y
tiene **modos de caos** para provocar los fallos que el terminal debe manejar.

## Cómo correrlo

```bash
./gradlew run    # ISO 8583 en :8583, administración HTTP en :8080
./gradlew test   # 16 pruebas, incluidas tres por TCP de verdad
```

Puertos por variables de entorno: `ISO_PORT`, `ADMIN_PORT`. Desde el emulador
de Android: `10.0.2.2:8583` y `http://10.0.2.2:8080`.

## Qué responde

| Caso | Código |
| --- | --- |
| Todo bien | 00 + código de autorización + RRN |
| Centavos terminados en .51 | 51 fondos insuficientes |
| Centavos terminados en .05 | 05 no honrar |
| Más de 10 000.00 | 61 excede el límite |
| PIN distinto de 1234 | 55 |
| Tarjeta vencida / Luhn inválido | 54 / 14 |
| MAC inválido o ausente | 63 |
| Misma llave (terminal, STAN, fecha-hora) con otro contenido | 94 |
| Mismo mensaje reenviado | la misma respuesta de antes, sin cobrar dos veces |
| Reversa 0400 | 00, y la original queda `REVERSED` (si no existía, 00 igual: no hay cobro vivo) |
| Aviso 0220 | 00, y queda `OFFLINE_APPROVED` |

## Modos de caos

```bash
curl -X PUT localhost:8080/chaos -H "Content-Type: application/json" \
     -d '{"mode":"TIMEOUT_AFTER_APPROVAL","delayMs":0}'
curl localhost:8080/transactions
```

| Modo | Qué hace |
| --- | --- |
| `NORMAL` | Responde |
| `SLOW` | Responde después de `delayMs` |
| `TIMEOUT_AFTER_APPROVAL` | **Aprueba y no responde**: el estado incierto, que el terminal debe resolver con reversa |
| `DROP_AFTER_APPROVAL` | Aprueba y corta la conexión |
| `DECLINE_ALL` | Todo 05 |
| `UNAVAILABLE` | Corta sin procesar: host caído |

Los dos modos "after approval" nunca afectan a las reversas: si no, no habría
forma de deshacer.

## Arquitectura

```
transport/IsoTcpServer   Ktor network: framing y modo caos
        │
domain/Authorizer        todas las reglas, sin red ni framework
   ├── Hsm (LabHsm)      BDK → llave del KSN → MAC y PIN
   └── Journal           bitácora + caché de idempotencia
admin/AdminApi           Ktor server: /health, /chaos, /transactions
```

`Authorizer` no sabe que existe Ktor, así que sus reglas se prueban en
milisegundos sin levantar un servidor.

## Lo que no es real

- **Las respuestas no llevan MAC**: hace falta la variante de MAC de respuesta
  de DUKPT, que todavía no está contrastada en `dukpt-kt`.
- El HSM es una clase: en producción la BDK vive en un HSM físico y el PIN se
  traduce a la llave del emisor sin salir en claro.
- No hay clearing ni settlement: las ventas 0200 se tratan como autorización
  más captura.
- La bitácora vive en memoria y se pierde al reiniciar.
