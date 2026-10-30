package io.github.marlonpya.gateway.domain

import io.github.marlonpya.dukpt.Dukpt
import io.github.marlonpya.dukpt.Ksn
import io.github.marlonpya.dukpt.PinBlock

/**
 * Lo que en producción hace el HSM del adquirente: tener la BDK y, con el KSN
 * de cada mensaje, rederivar la llave del terminal para verificar el MAC y
 * descifrar el PIN.
 *
 * En un HSM real el PIN tampoco sale en claro: el HSM lo traduce de la llave
 * del terminal a la del emisor (PIN translation) o lo compara él mismo. Aquí
 * [verifyPin] compara dentro de la clase y solo devuelve sí o no, que es la
 * misma frontera.
 */
interface Hsm {
    fun macKey(ksn: Ksn): ByteArray

    fun verifyPin(ksn: Ksn, encryptedPinBlock: ByteArray, pan: String): Boolean
}

class LabHsm(bdk: ByteArray, private val expectedPin: String = "1234") : Hsm {

    private val bdk = bdk.copyOf()

    override fun macKey(ksn: Ksn): ByteArray = Dukpt.macRequestKey(Dukpt.transactionKey(bdk, ksn))

    override fun verifyPin(ksn: Ksn, encryptedPinBlock: ByteArray, pan: String): Boolean {
        val key = Dukpt.transactionKey(bdk, ksn)
        return try {
            PinBlock.decrypt(key, encryptedPinBlock, pan) == expectedPin
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            key.fill(0)
        }
    }
}
