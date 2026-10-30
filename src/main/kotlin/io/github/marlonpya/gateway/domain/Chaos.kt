package io.github.marlonpya.gateway.domain

/**
 * Cómo se porta el gateway, para provocar desde afuera los fallos que el
 * terminal tiene que saber manejar.
 *
 * Los modos peligrosos de verdad son [TIMEOUT_AFTER_APPROVAL] y
 * [DROP_AFTER_APPROVAL]: el host aprueba, descuenta el dinero y la respuesta
 * nunca llega. Para el terminal es un estado incierto, y la única respuesta
 * correcta es reversar. Solo afectan a solicitudes financieras y de
 * autorización: las reversas pasan siempre, porque si no, no habría forma de
 * deshacer.
 */
enum class GatewayMode {
    NORMAL,

    /** Responde, pero después de [ChaosSettings.delayMs]. */
    SLOW,

    /** Aprueba y no responde nunca. */
    TIMEOUT_AFTER_APPROVAL,

    /** Aprueba y corta la conexión. */
    DROP_AFTER_APPROVAL,

    /** Rechaza todo con 05. */
    DECLINE_ALL,

    /** Corta la conexión sin procesar nada: el host está caído. */
    UNAVAILABLE,
}

class ChaosSettings {
    @Volatile
    var mode: GatewayMode = GatewayMode.NORMAL

    @Volatile
    var delayMs: Long = 3_000
}
