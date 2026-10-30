package io.github.marlonpya.gateway.domain

import java.time.Instant

/**
 * Códigos de respuesta (campo 39) que usa el laboratorio, con su significado
 * en ISO 8583:1987. Cada procesador agrega los suyos; estos son los comunes.
 */
object ResponseCode {
    const val APPROVED = "00"
    const val DO_NOT_HONOR = "05"
    const val INVALID_TRANSACTION = "12"
    const val INVALID_AMOUNT = "13"
    const val INVALID_CARD_NUMBER = "14"
    const val FORMAT_ERROR = "30"
    const val INSUFFICIENT_FUNDS = "51"
    const val EXPIRED_CARD = "54"
    const val INCORRECT_PIN = "55"
    const val EXCEEDS_AMOUNT_LIMIT = "61"
    const val SECURITY_VIOLATION = "63"
    const val DUPLICATE_TRANSMISSION = "94"
    const val SYSTEM_MALFUNCTION = "96"
}

/**
 * Identifica una transacción del lado del terminal: el mismo terminal no
 * repite STAN con la misma fecha y hora de transmisión. Es la llave de
 * idempotencia y también la que usa una reversa para encontrar a su original.
 */
data class TxKey(val terminalId: String, val stan: String, val transmissionDateTime: String)

enum class TxStatus { APPROVED, DECLINED, REVERSED, OFFLINE_APPROVED }

/** Lo que el gateway recuerda de cada transacción. Nunca guarda PAN completo, pista ni PIN. */
data class JournalEntry(
    val key: TxKey,
    val mti: String,
    val maskedPan: String?,
    val amountCents: Long?,
    val responseCode: String,
    val authCode: String?,
    val rrn: String?,
    val ksn: String?,
    val status: TxStatus,
    val receivedAt: Instant,
)
