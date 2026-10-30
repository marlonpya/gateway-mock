package io.github.marlonpya.gateway

import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.gateway.admin.adminModule
import io.github.marlonpya.gateway.domain.Authorizer
import io.github.marlonpya.gateway.domain.ChaosSettings
import io.github.marlonpya.gateway.domain.Journal
import io.github.marlonpya.gateway.domain.LabHsm
import io.github.marlonpya.gateway.transport.IsoTcpServer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope

/**
 * BDK de prueba de ANSI X9.24-1, la misma con que el simulador de PINpad
 * recibe su IPEK. Nunca una BDK real en un repositorio: en producción vive en
 * un HSM y la app no la ve nunca.
 */
private const val TEST_BDK = "0123456789ABCDEFFEDCBA9876543210"

suspend fun main(): Unit = coroutineScope {
    val isoPort = System.getenv("ISO_PORT")?.toInt() ?: 8583
    val adminPort = System.getenv("ADMIN_PORT")?.toInt() ?: 8080

    val journal = Journal()
    val chaos = ChaosSettings()
    val authorizer = Authorizer(LabHsm(TEST_BDK.hexToBytes()), journal, chaos)

    val iso = IsoTcpServer(authorizer, chaos)
    iso.start(this, isoPort)
    embeddedServer(CIO, port = adminPort) { adminModule(journal, chaos) }.start(wait = false)

    println("gateway-mock: ISO 8583 en el puerto ${iso.port}, administración en http://localhost:$adminPort")
    println("Desde el emulador de Android: 10.0.2.2:${iso.port} y http://10.0.2.2:$adminPort")
    // Mantiene vivo el proceso hasta Ctrl+C.
    awaitCancellation()
}
