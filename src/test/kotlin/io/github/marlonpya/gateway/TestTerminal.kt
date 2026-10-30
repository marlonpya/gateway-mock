package io.github.marlonpya.gateway

import io.github.marlonpya.dukpt.Dukpt
import io.github.marlonpya.dukpt.Ksn
import io.github.marlonpya.dukpt.PinBlock
import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.IsoCodec
import io.github.marlonpya.iso8583.IsoMessage
import io.github.marlonpya.iso8583.MessageMac
import io.github.marlonpya.iso8583.Mti

/**
 * Hace en las pruebas lo que harían juntos la app y el PINpad: arma el
 * mensaje, cifra el PIN y lo firma con la llave DUKPT de la transacción.
 */
class TestTerminal(private val terminalId: String = "LAB00001") {

    val bdk = "0123456789ABCDEFFEDCBA9876543210".hexToBytes()
    private var ksn = Ksn.parse("FFFF9876543210E00000")
    private val ipek = Dukpt.deriveIpek(bdk, ksn)
    private val codec = IsoCodec()
    private val mac = MessageMac(codec)
    private var stan = 0

    fun sale(
        amountCents: Long,
        pin: String? = "1234",
        track2: String = "4111111111111111=30122010000000000000",
        datetime: String = "1030153000",
    ): ByteArray {
        ksn = ksn.next()
        val key = Dukpt.deriveTransactionKey(ipek, ksn)
        var m = IsoMessage.of(Mti.FINANCIAL_REQUEST)
            .with(Fields.PROCESSING_CODE, "000000")
            .with(Fields.AMOUNT, amountCents.toString())
            .with(Fields.TRANSMISSION_DATETIME, datetime)
            .with(Fields.STAN, (++stan).toString())
            .with(Fields.ENTRY_MODE, "051")
            .with(Fields.TRACK2, track2)
            .with(Fields.TERMINAL_ID, terminalId)
            .with(Fields.MERCHANT_ID, "COMERCIO-LAB")
            .with(Fields.CURRENCY, "604")
            .with(Fields.KSN, ksn.toHex())
        if (pin != null) m = m.with(Fields.PIN_BLOCK, PinBlock.encrypt(key, pin, track2.takeWhile { it.isDigit() }))
        return codec.pack(mac.sign(m, Dukpt.macRequestKey(key)))
    }

    /** Reversa de una venta ya armada: identifica a la original por el campo 90. */
    fun reversalOf(saleRaw: ByteArray, datetime: String = "1030153100"): ByteArray {
        val original = codec.unpack(saleRaw)
        ksn = ksn.next()
        val key = Dukpt.deriveTransactionKey(ipek, ksn)
        val m = IsoMessage.of(Mti.REVERSAL_REQUEST)
            .with(Fields.PROCESSING_CODE, "000000")
            .with(Fields.AMOUNT, original.text(Fields.AMOUNT))
            .with(Fields.TRANSMISSION_DATETIME, datetime)
            .with(Fields.STAN, (++stan).toString())
            .with(Fields.TERMINAL_ID, terminalId)
            .with(Fields.KSN, ksn.toHex())
            .with(
                Fields.ORIGINAL_DATA,
                original.mti.code + original.text(Fields.STAN) + original.text(Fields.TRANSMISSION_DATETIME) + "0".repeat(22),
            )
        return codec.pack(mac.sign(m, Dukpt.macRequestKey(key)))
    }

    fun echo(): ByteArray = codec.pack(
        IsoMessage.of(Mti.NETWORK_REQUEST)
            .with(Fields.TRANSMISSION_DATETIME, "1030150000")
            .with(Fields.STAN, (++stan).toString())
            .with(Fields.TERMINAL_ID, terminalId),
    )

    fun unpack(raw: ByteArray) = codec.unpack(raw)
}
