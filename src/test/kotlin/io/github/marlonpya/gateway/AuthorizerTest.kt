package io.github.marlonpya.gateway

import io.github.marlonpya.gateway.domain.Authorizer
import io.github.marlonpya.gateway.domain.ChaosSettings
import io.github.marlonpya.gateway.domain.GatewayMode
import io.github.marlonpya.gateway.domain.Journal
import io.github.marlonpya.gateway.domain.LabHsm
import io.github.marlonpya.gateway.domain.ResponseCode
import io.github.marlonpya.gateway.domain.TxStatus
import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.Mti
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizerTest {

    private val terminal = TestTerminal()
    private val journal = Journal()
    private val chaos = ChaosSettings()
    private val clock = Clock.fixed(Instant.parse("2026-10-30T15:30:00Z"), ZoneOffset.UTC)
    private val authorizer = Authorizer(LabHsm(terminal.bdk), journal, chaos, clock)

    private fun code(raw: ByteArray) = authorizer.handle(raw).response!!.text(Fields.RESPONSE_CODE)

    @Test
    @DisplayName("Una venta con PIN y MAC correctos se aprueba con código de autorización y RRN")
    fun approved() {
        val response = authorizer.handle(terminal.sale(2550)).response!!
        assertEquals(Mti.FINANCIAL_RESPONSE, response.mti)
        assertEquals(ResponseCode.APPROVED, response.text(Fields.RESPONSE_CODE))
        assertEquals(6, response.text(Fields.AUTH_CODE).length)
        assertEquals(12, response.text(Fields.RRN).length)
        assertEquals(TxStatus.APPROVED, journal.all().single().status)
    }

    @Test
    @DisplayName("Los montos de prueba provocan cada código de rechazo")
    fun declineByAmount() {
        assertEquals(ResponseCode.INSUFFICIENT_FUNDS, code(terminal.sale(2551)))
        assertEquals(ResponseCode.DO_NOT_HONOR, code(terminal.sale(2505)))
        assertEquals(ResponseCode.EXCEEDS_AMOUNT_LIMIT, code(terminal.sale(1_000_001)))
    }

    @Test
    @DisplayName("Un PIN equivocado se rechaza con 55 sin que el PIN salga del HSM")
    fun wrongPin() {
        assertEquals(ResponseCode.INCORRECT_PIN, code(terminal.sale(2550, pin = "9999")))
    }

    @Test
    @DisplayName("Sin PIN block, como un contactless bajo el límite, se aprueba igual")
    fun noPin() {
        assertEquals(ResponseCode.APPROVED, code(terminal.sale(1000, pin = null)))
    }

    @Test
    @DisplayName("Una tarjeta vencida se rechaza con 54 y un PAN con Luhn inválido con 14")
    fun cardChecks() {
        assertEquals(ResponseCode.EXPIRED_CARD, code(terminal.sale(2550, track2 = "4111111111111111=24012010000000000000")))
        assertEquals(ResponseCode.INVALID_CARD_NUMBER, code(terminal.sale(2550, track2 = "4111111111111112=30122010000000000000")))
    }

    @Test
    @DisplayName("Un mensaje alterado en el camino falla el MAC y se rechaza con 63")
    fun tamperedMac() {
        val raw = terminal.sale(2550)
        // Byte 29: último dígito del monto.
        raw[29] = '9'.code.toByte()
        assertEquals(ResponseCode.SECURITY_VIOLATION, code(raw))
        assertTrue(journal.all().isEmpty())
    }

    @Test
    @DisplayName("El mismo mensaje reenviado recibe la misma respuesta y no cobra dos veces")
    fun idempotent() {
        val raw = terminal.sale(2550)
        val first = authorizer.handle(raw).response!!
        val second = authorizer.handle(raw).response!!
        assertEquals(first, second)
        assertEquals(1, journal.all().size)
    }

    @Test
    @DisplayName("La misma llave con otro contenido es una transmisión duplicada (94)")
    fun conflictingDuplicate() {
        val a = TestTerminal()
        val b = TestTerminal()
        authorizer.handle(a.sale(2550))
        // Mismo terminal, STAN y fecha-hora, otro monto.
        assertEquals(ResponseCode.DUPLICATE_TRANSMISSION, code(b.sale(9900)))
    }

    @Test
    @DisplayName("Una reversa marca la original como reversada")
    fun reversal() {
        val sale = terminal.sale(2550)
        authorizer.handle(sale)
        val response = authorizer.handle(terminal.reversalOf(sale)).response!!
        assertEquals(Mti.REVERSAL_RESPONSE, response.mti)
        assertEquals(ResponseCode.APPROVED, response.text(Fields.RESPONSE_CODE))
        assertEquals(TxStatus.REVERSED, journal.all().single().status)
    }

    @Test
    @DisplayName("La reversa de una venta que nunca llegó responde 00: no queda cobro vivo")
    fun reversalOfUnknown() {
        val sale = terminal.sale(2550)
        assertEquals(ResponseCode.APPROVED, code(terminal.reversalOf(sale)))
        assertTrue(journal.all().isEmpty())
    }

    @Test
    @DisplayName("La gestión de red no necesita MAC")
    fun echo() {
        val response = authorizer.handle(terminal.echo()).response!!
        assertEquals(Mti.NETWORK_RESPONSE, response.mti)
        assertEquals(ResponseCode.APPROVED, response.text(Fields.RESPONSE_CODE))
    }

    @Test
    @DisplayName("En modo DECLINE_ALL todo se rechaza con 05")
    fun declineAll() {
        chaos.mode = GatewayMode.DECLINE_ALL
        assertEquals(ResponseCode.DO_NOT_HONOR, code(terminal.sale(2550)))
    }

    @Test
    @DisplayName("Bytes ilegibles no producen respuesta y la bitácora nunca guarda el PAN completo")
    fun garbageAndMasking() {
        assertNull(authorizer.handle(byteArrayOf(1, 2, 3)).response)
        authorizer.handle(terminal.sale(2550))
        val entry = journal.all().single()
        assertEquals("411111******1111", entry.maskedPan)
        assertNotNull(entry.ksn)
        assertTrue("4111111111111111" !in entry.toString())
    }
}
