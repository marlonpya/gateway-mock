package io.github.marlonpya.gateway.domain

import io.github.marlonpya.iso8583.IsoMessage
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Bitácora de transacciones y caché de idempotencia, en memoria.
 *
 * La caché guarda, por cada solicitud, el hash de sus bytes y la respuesta que
 * se dio. Si el terminal reenvía exactamente el mismo mensaje (porque no le
 * llegó la respuesta), recibe la misma respuesta y no se cobra dos veces. Si
 * reenvía la misma llave con otro contenido, es un error del terminal y se
 * responde 94.
 */
class Journal {

    private data class Cached(val requestHash: String, val response: IsoMessage)

    private data class RequestKey(val mti: String, val key: TxKey)

    private val entries = ConcurrentHashMap<TxKey, JournalEntry>()
    private val responses = ConcurrentHashMap<RequestKey, Cached>()

    fun record(entry: JournalEntry) {
        entries[entry.key] = entry
    }

    fun find(key: TxKey): JournalEntry? = entries[key]

    fun update(key: TxKey, transform: (JournalEntry) -> JournalEntry): JournalEntry? =
        entries.computeIfPresent(key) { _, e -> transform(e) }

    fun all(): List<JournalEntry> = entries.values.sortedBy { it.receivedAt }

    fun clear() {
        entries.clear()
        responses.clear()
    }

    /** Resultado de buscar una solicitud en la caché. */
    sealed interface Replay {
        data object New : Replay

        data class Same(val response: IsoMessage) : Replay

        data object Conflict : Replay
    }

    fun replay(mti: String, key: TxKey, raw: ByteArray): Replay {
        val cached = responses[RequestKey(mti, key)] ?: return Replay.New
        return if (cached.requestHash == hash(raw)) Replay.Same(cached.response) else Replay.Conflict
    }

    fun remember(mti: String, key: TxKey, raw: ByteArray, response: IsoMessage) {
        responses.putIfAbsent(RequestKey(mti, key), Cached(hash(raw), response))
    }

    private fun hash(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
}
