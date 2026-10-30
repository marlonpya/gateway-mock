package io.github.marlonpya.gateway.domain

import io.github.marlonpya.dukpt.Ksn
import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.IsoCodec
import io.github.marlonpya.iso8583.IsoFormatException
import io.github.marlonpya.iso8583.IsoMessage
import io.github.marlonpya.iso8583.MessageMac
import io.github.marlonpya.iso8583.Mti
import io.github.marlonpya.iso8583.Pan
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Las reglas del host, sin red ni framework: entra un mensaje en bytes y sale
 * la respuesta. No sabe que existe Ktor, así que se prueba en milisegundos.
 *
 * Reglas de decisión, para provocar cada respuesta desde la app con montos:
 *  - centavos terminados en 51 → 51 fondos insuficientes,
 *  - centavos terminados en 05 → 05 no honrar,
 *  - más de 10 000.00 → 61 excede el límite,
 *  - PIN distinto de 1234 → 55, tarjeta vencida → 54, Luhn inválido → 14,
 *  - todo lo demás → 00.
 */
class Authorizer(
    private val hsm: Hsm,
    private val journal: Journal,
    private val chaos: ChaosSettings = ChaosSettings(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val codec: IsoCodec = IsoCodec(),
) {
    private val mac = MessageMac(codec)
    private val sequence = AtomicLong()

    /** La respuesta y si la solicitud era de las que mueven dinero (para el modo caos). */
    data class Outcome(val response: IsoMessage?, val moneyMoving: Boolean)

    fun handle(raw: ByteArray): Outcome {
        val request = try {
            codec.unpack(raw)
        } catch (_: IsoFormatException) {
            // Sin un mensaje legible no hay con qué armar una respuesta emparejable.
            return Outcome(null, moneyMoving = false)
        }
        return when (request.mti) {
            Mti.NETWORK_REQUEST -> Outcome(request.toResponse(ResponseCode.APPROVED), moneyMoving = false)
            Mti.FINANCIAL_REQUEST, Mti.AUTHORIZATION_REQUEST -> Outcome(financial(request, raw), moneyMoving = true)
            Mti.FINANCIAL_ADVICE -> Outcome(advice(request, raw), moneyMoving = false)
            Mti.REVERSAL_REQUEST -> Outcome(reversal(request, raw), moneyMoving = false)
            else -> Outcome(request.toResponse(ResponseCode.INVALID_TRANSACTION), moneyMoving = false)
        }
    }

    private fun financial(request: IsoMessage, raw: ByteArray): IsoMessage {
        val key = txKey(request) ?: return request.toResponse(ResponseCode.FORMAT_ERROR)
        securityCheck(request, raw)?.let { return request.toResponse(it) }
        replayed(request, key, raw)?.let { return it }

        val code = decide(request)
        val approved = code == ResponseCode.APPROVED
        var response = request.toResponse(code).with(Fields.RRN, nextRrn())
        if (approved) response = response.with(Fields.AUTH_CODE, nextAuthCode())

        journal.record(entry(request, key, response, if (approved) TxStatus.APPROVED else TxStatus.DECLINED))
        journal.remember(request.mti.code, key, raw, response)
        return response
    }

    /** Aviso de una venta que el terminal aprobó sin conexión (store and forward). */
    private fun advice(request: IsoMessage, raw: ByteArray): IsoMessage {
        val key = txKey(request) ?: return request.toResponse(ResponseCode.FORMAT_ERROR)
        securityCheck(request, raw)?.let { return request.toResponse(it) }
        replayed(request, key, raw)?.let { return it }

        val response = request.toResponse(ResponseCode.APPROVED).with(Fields.RRN, nextRrn())
        journal.record(entry(request, key, response, TxStatus.OFFLINE_APPROVED))
        journal.remember(request.mti.code, key, raw, response)
        return response
    }

    /**
     * Reversa: deshace la original que identifica el campo 90.
     *
     * Si la original no existe (porque nunca llegó al host), igual se responde
     * 00: el terminal quiere saber que no quedó un cobro vivo, y no lo hay.
     */
    private fun reversal(request: IsoMessage, raw: ByteArray): IsoMessage {
        val key = txKey(request) ?: return request.toResponse(ResponseCode.FORMAT_ERROR)
        securityCheck(request, raw)?.let { return request.toResponse(it) }
        replayed(request, key, raw)?.let { return it }

        val original = request.textOrNull(Fields.ORIGINAL_DATA)
            ?.takeIf { it.length == 42 }
            ?: return request.toResponse(ResponseCode.FORMAT_ERROR)
        val originalKey = TxKey(key.terminalId, original.substring(4, 10), original.substring(10, 20))
        journal.update(originalKey) { if (it.status == TxStatus.DECLINED) it else it.copy(status = TxStatus.REVERSED) }

        val response = request.toResponse(ResponseCode.APPROVED)
        journal.remember(request.mti.code, key, raw, response)
        return response
    }

    /** MAC obligatorio en todo lo que no sea gestión de red, verificado con la llave del KSN que trae el mensaje. */
    private fun securityCheck(request: IsoMessage, raw: ByteArray): String? {
        val ksnHex = request.textOrNull(Fields.KSN) ?: return ResponseCode.SECURITY_VIOLATION
        val ksn = runCatching { Ksn.parse(ksnHex) }.getOrNull() ?: return ResponseCode.FORMAT_ERROR
        val valid = runCatching { mac.verify(raw, hsm.macKey(ksn)) }.getOrDefault(false)
        return if (valid) null else ResponseCode.SECURITY_VIOLATION
    }

    private fun replayed(request: IsoMessage, key: TxKey, raw: ByteArray): IsoMessage? =
        when (val r = journal.replay(request.mti.code, key, raw)) {
            Journal.Replay.New -> null
            is Journal.Replay.Same -> r.response
            Journal.Replay.Conflict -> request.toResponse(ResponseCode.DUPLICATE_TRANSMISSION)
        }

    private fun decide(request: IsoMessage): String {
        if (chaos.mode == GatewayMode.DECLINE_ALL) return ResponseCode.DO_NOT_HONOR

        val amount = request.textOrNull(Fields.AMOUNT)?.toLongOrNull() ?: return ResponseCode.FORMAT_ERROR
        if (amount <= 0) return ResponseCode.INVALID_AMOUNT

        val track2 = request.textOrNull(Fields.TRACK2)
        val pan = request.textOrNull(Fields.PAN) ?: track2?.let(Pan::fromTrack2) ?: return ResponseCode.FORMAT_ERROR
        if (!Pan.isValidLuhn(pan)) return ResponseCode.INVALID_CARD_NUMBER
        if (isExpired(request, track2)) return ResponseCode.EXPIRED_CARD

        val pinBlock = request.bytesOrNull(Fields.PIN_BLOCK)
        if (pinBlock != null && !hsm.verifyPin(Ksn.parse(request.text(Fields.KSN)), pinBlock, pan)) {
            return ResponseCode.INCORRECT_PIN
        }

        if (amount > AMOUNT_LIMIT_CENTS) return ResponseCode.EXCEEDS_AMOUNT_LIMIT
        return when (amount % 100) {
            51L -> ResponseCode.INSUFFICIENT_FUNDS
            5L -> ResponseCode.DO_NOT_HONOR
            else -> ResponseCode.APPROVED
        }
    }

    private fun isExpired(request: IsoMessage, track2: String?): Boolean {
        val yymm = request.textOrNull(Fields.EXPIRY)
            // En la pista 2, la expiración son los 4 dígitos después del separador (= o D).
            ?: track2?.dropWhile { it.isDigit() }?.drop(1)?.take(4)
            ?: return false
        if (yymm.length != 4 || !yymm.all { it.isDigit() }) return false
        val expiry = YearMonth.of(2000 + yymm.substring(0, 2).toInt(), yymm.substring(2, 4).toInt().coerceIn(1, 12))
        return expiry.isBefore(YearMonth.now(clock))
    }

    private fun txKey(m: IsoMessage): TxKey? {
        val tid = m.textOrNull(Fields.TERMINAL_ID) ?: return null
        val stan = m.textOrNull(Fields.STAN) ?: return null
        val f7 = m.textOrNull(Fields.TRANSMISSION_DATETIME) ?: return null
        return TxKey(tid.trim(), stan, f7)
    }

    private fun entry(request: IsoMessage, key: TxKey, response: IsoMessage, status: TxStatus) = JournalEntry(
        key = key,
        mti = request.mti.code,
        maskedPan = (request.textOrNull(Fields.PAN) ?: request.textOrNull(Fields.TRACK2)?.let(Pan::fromTrack2))?.let(Pan::mask),
        amountCents = request.textOrNull(Fields.AMOUNT)?.toLongOrNull(),
        responseCode = response.text(Fields.RESPONSE_CODE),
        authCode = response.textOrNull(Fields.AUTH_CODE),
        rrn = response.textOrNull(Fields.RRN),
        ksn = request.textOrNull(Fields.KSN),
        status = status,
        receivedAt = Instant.now(clock),
    )

    /** RRN de 12: año (1), día del año (3), hora (2) y un correlativo (6). */
    private fun nextRrn(): String {
        val now = clock.instant().atZone(ZoneId.systemDefault())
        val prefix = now.format(DateTimeFormatter.ofPattern("yDDDHH")).takeLast(6)
        return prefix + (sequence.incrementAndGet() % 1_000_000).toString().padStart(6, '0')
    }

    private fun nextAuthCode(): String = "A" + (sequence.get() % 100_000).toString().padStart(5, '0')

    companion object {
        const val AMOUNT_LIMIT_CENTS = 1_000_000L
    }
}
