package io.github.marlonpya.gateway.admin

import io.github.marlonpya.gateway.domain.ChaosSettings
import io.github.marlonpya.gateway.domain.GatewayMode
import io.github.marlonpya.gateway.domain.Journal
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class ChaosDto(val mode: GatewayMode, val delayMs: Long)

@Serializable
data class TransactionDto(
    val terminalId: String,
    val stan: String,
    val transmissionDateTime: String,
    val mti: String,
    val maskedPan: String?,
    val amountCents: Long?,
    val responseCode: String,
    val authCode: String?,
    val rrn: String?,
    val ksn: String?,
    val status: String,
    val receivedAt: String,
)

/**
 * API HTTP de administración: lo que usa el panel de caos de la app y lo que
 * miras con curl durante la demo. No es parte del protocolo de pagos.
 */
fun Application.adminModule(journal: Journal, chaos: ChaosSettings) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok", "mode" to chaos.mode.name)) }

        get("/chaos") { call.respond(ChaosDto(chaos.mode, chaos.delayMs)) }

        put("/chaos") {
            val dto = call.receive<ChaosDto>()
            chaos.mode = dto.mode
            chaos.delayMs = dto.delayMs.coerceIn(0, 120_000)
            call.respond(ChaosDto(chaos.mode, chaos.delayMs))
        }

        get("/transactions") {
            call.respond(
                journal.all().map {
                    TransactionDto(
                        terminalId = it.key.terminalId,
                        stan = it.key.stan,
                        transmissionDateTime = it.key.transmissionDateTime,
                        mti = it.mti,
                        maskedPan = it.maskedPan,
                        amountCents = it.amountCents,
                        responseCode = it.responseCode,
                        authCode = it.authCode,
                        rrn = it.rrn,
                        ksn = it.ksn,
                        status = it.status.name,
                        receivedAt = it.receivedAt.toString(),
                    )
                },
            )
        }

        delete("/transactions") {
            journal.clear()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
