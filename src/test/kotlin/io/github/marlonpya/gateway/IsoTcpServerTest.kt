package io.github.marlonpya.gateway

import io.github.marlonpya.gateway.domain.Authorizer
import io.github.marlonpya.gateway.domain.ChaosSettings
import io.github.marlonpya.gateway.domain.GatewayMode
import io.github.marlonpya.gateway.domain.Journal
import io.github.marlonpya.gateway.domain.LabHsm
import io.github.marlonpya.gateway.domain.TxStatus
import io.github.marlonpya.gateway.transport.IsoTcpServer
import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.LengthPrefixFraming
import io.github.marlonpya.iso8583.Mti
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.DataInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertEquals

/** El servidor de verdad, sobre un socket TCP de verdad, en un puerto libre. */
class IsoTcpServerTest {

    private val terminal = TestTerminal()
    private val journal = Journal()
    private val chaos = ChaosSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = IsoTcpServer(Authorizer(LabHsm(terminal.bdk), journal, chaos), chaos)

    @BeforeEach
    fun start() = runBlocking { server.start(scope, 0); Unit }

    @AfterEach
    fun stop() {
        server.stop()
        scope.cancel()
    }

    private fun connect() = Socket("127.0.0.1", server.port).apply { soTimeout = 2_000 }

    private fun Socket.exchange(raw: ByteArray): ByteArray {
        getOutputStream().apply { write(LengthPrefixFraming.frame(raw)); flush() }
        val input = DataInputStream(getInputStream())
        val header = ByteArray(2).also { input.readFully(it) }
        return ByteArray(LengthPrefixFraming.readLength(header)).also { input.readFully(it) }
    }

    @Test
    @DisplayName("Echo y venta por TCP en la misma conexión")
    fun echoAndSale() {
        connect().use { s ->
            assertEquals(Mti.NETWORK_RESPONSE, terminal.unpack(s.exchange(terminal.echo())).mti)
            val sale = terminal.unpack(s.exchange(terminal.sale(2550)))
            assertEquals("00", sale.text(Fields.RESPONSE_CODE))
        }
    }

    @Test
    @DisplayName("Modo TIMEOUT_AFTER_APPROVAL: el host aprueba, no responde, y la reversa lo deshace")
    fun timeoutThenReversal() {
        chaos.mode = GatewayMode.TIMEOUT_AFTER_APPROVAL
        val sale = terminal.sale(2550)
        connect().use { s ->
            assertThrows<SocketTimeoutException> { s.exchange(sale) }
        }
        // El cobro quedó vivo del lado del host: es el estado incierto.
        assertEquals(TxStatus.APPROVED, journal.all().single().status)

        connect().use { s ->
            val response = terminal.unpack(s.exchange(terminal.reversalOf(sale)))
            assertEquals(Mti.REVERSAL_RESPONSE, response.mti)
        }
        assertEquals(TxStatus.REVERSED, journal.all().single().status)
    }

    @Test
    @DisplayName("Modo UNAVAILABLE: la conexión se corta sin procesar nada")
    fun unavailable() {
        chaos.mode = GatewayMode.UNAVAILABLE
        connect().use { s ->
            assertThrows<java.io.EOFException> { s.exchange(terminal.sale(2550)) }
        }
        assertEquals(0, journal.all().size)
    }
}
