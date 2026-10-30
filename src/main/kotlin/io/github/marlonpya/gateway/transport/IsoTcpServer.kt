package io.github.marlonpya.gateway.transport

import io.github.marlonpya.gateway.domain.Authorizer
import io.github.marlonpya.gateway.domain.ChaosSettings
import io.github.marlonpya.gateway.domain.GatewayMode
import io.github.marlonpya.iso8583.IsoCodec
import io.github.marlonpya.iso8583.LengthPrefixFraming
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Servidor ISO 8583 sobre TCP: cada mensaje viene precedido por dos bytes con
 * su largo ([LengthPrefixFraming]). Varias conexiones a la vez, cada una en su
 * corrutina, y en cada conexión los mensajes se atienden en orden.
 *
 * Aquí, y solo aquí, se aplica el modo caos: el [Authorizer] decide igual que
 * siempre y el transporte decide si la respuesta llega, tarde o nunca.
 */
class IsoTcpServer(
    private val authorizer: Authorizer,
    private val chaos: ChaosSettings,
    private val codec: IsoCodec = IsoCodec(),
) {
    private val log = LoggerFactory.getLogger("iso8583")
    private val selector = SelectorManager(Dispatchers.IO)
    private lateinit var server: ServerSocket

    val port: Int get() = (server.localAddress as io.ktor.network.sockets.InetSocketAddress).port

    /** Abre el puerto (0 elige uno libre) y empieza a aceptar conexiones en [scope]. */
    suspend fun start(scope: CoroutineScope, port: Int): Job {
        server = aSocket(selector).tcp().bind("0.0.0.0", port)
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = server.accept()
                launch { serve(socket) }
            }
        }
    }

    fun stop() {
        server.close()
        selector.close()
    }

    private suspend fun serve(socket: Socket) {
        val peer = socket.remoteAddress
        log.info("Terminal conectado desde {}", peer)
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        try {
            while (true) {
                val raw = readMessage(input) ?: break
                if (chaos.mode == GatewayMode.UNAVAILABLE) {
                    log.warn("Modo UNAVAILABLE: se corta la conexión sin procesar")
                    break
                }
                val outcome = authorizer.handle(raw)
                val response = outcome.response
                if (response == null) {
                    log.warn("Mensaje ilegible de {} bytes, se cierra la conexión", raw.size)
                    break
                }
                log.info("{} -> {} {}", codec.unpack(raw).mti, response.mti, response.textOrNull(39))

                if (outcome.moneyMoving) {
                    when (chaos.mode) {
                        GatewayMode.TIMEOUT_AFTER_APPROVAL -> {
                            log.warn("Modo TIMEOUT_AFTER_APPROVAL: procesado, la respuesta no sale")
                            continue
                        }
                        GatewayMode.DROP_AFTER_APPROVAL -> {
                            log.warn("Modo DROP_AFTER_APPROVAL: procesado, se corta la conexión")
                            break
                        }
                        GatewayMode.SLOW -> delay(chaos.delayMs)
                        else -> Unit
                    }
                }
                output.writeFully(LengthPrefixFraming.frame(codec.pack(response)))
            }
        } catch (e: Exception) {
            log.info("Conexión con {} terminada: {}", peer, e.javaClass.simpleName)
        } finally {
            socket.close()
        }
    }

    /** Un mensaje completo, o `null` si el terminal cerró la conexión. */
    private suspend fun readMessage(input: ByteReadChannel): ByteArray? {
        val header = ByteArray(2)
        try {
            input.readFully(header)
        } catch (_: Exception) {
            return null
        }
        val body = ByteArray(LengthPrefixFraming.readLength(header))
        input.readFully(body)
        return body
    }
}
