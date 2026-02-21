package com.example.lxprint.ble

import com.example.lxprint.util.Crc16Xmodem
import java.util.UUID

object LxProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000FFE6-0000-1000-8000-00805F9B34FB")
    val SEND_CHAR_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
    val RECV_CHAR_UUID: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Message type constants
    const val MSG_PREFIX: Byte = 0x5A
    const val MSG_AUTH_HELLO: Byte = 0x01
    const val MSG_AUTH_STAGE2: Byte = 0x0A
    const val MSG_AUTH_STAGE3: Byte = 0x0B
    const val MSG_STATUS: Byte = 0x02
    const val MSG_PRINT: Byte = 0x04
    const val MSG_PRINT_COMPLETE: Byte = 0x06

    fun authHello(): ByteArray = byteArrayOf(MSG_PREFIX, MSG_AUTH_HELLO)

    /**
     * Auth stage 2: send 10 random bytes prepended with header.
     * Returns the message to send AND the computed CRC values for stage 3.
     */
    fun authStage2(mac: ByteArray): Pair<ByteArray, IntArray> {
        val randomBytes = ByteArray(10)
        java.security.SecureRandom().nextBytes(randomBytes)

        val crcs = IntArray(10) { i ->
            val buf = ByteArray(7)
            buf[0] = randomBytes[i]
            mac.copyInto(buf, destinationOffset = 1)
            Crc16Xmodem.compute(buf)
        }

        val msg = ByteArray(12)
        msg[0] = MSG_PREFIX
        msg[1] = MSG_AUTH_STAGE2
        randomBytes.copyInto(msg, destinationOffset = 2)
        return Pair(msg, crcs)
    }

    /**
     * Auth stage 3: send high bytes of each CRC.
     */
    fun authStage3(crcs: IntArray): ByteArray {
        val msg = ByteArray(12)
        msg[0] = MSG_PREFIX
        msg[1] = MSG_AUTH_STAGE3
        for (i in crcs.indices) {
            msg[2 + i] = (crcs[i] shr 8).toByte()
        }
        return msg
    }

    fun parseMac(msg: ByteArray): ByteArray = msg.sliceArray(4 until 10)

    fun isAuthSuccess(msg: ByteArray): Boolean =
        msg.size >= 3 && msg[0] == MSG_PREFIX && msg[1] == MSG_AUTH_STAGE3 && msg[2] == 1.toByte()

    data class PrinterStatus(
        val battery: Int,
        val noPaper: Boolean,
        val charging: Boolean,
        val overheat: Boolean,
        val lowBatt: Boolean,
        val density: Int,
        val voltage: Int,
    )

    fun parseStatus(msg: ByteArray): PrinterStatus? {
        if (msg.size < 12 || msg[0] != MSG_PREFIX || msg[1] != MSG_STATUS) return null
        return PrinterStatus(
            battery = msg[2].toInt() and 0xFF,
            noPaper = msg[3] != 0.toByte(),
            charging = msg[4] != 0.toByte(),
            overheat = msg[5] != 0.toByte(),
            lowBatt = msg[6] != 0.toByte(),
            density = msg[7].toInt() and 0xFF,
            voltage = ((msg[8].toInt() and 0xFF) shl 8) or (msg[9].toInt() and 0xFF),
        )
    }

    fun printInit(printLength: Int): ByteArray {
        val msg = ByteArray(6)
        msg[0] = MSG_PREFIX
        msg[1] = MSG_PRINT
        msg[2] = (printLength shr 8).toByte()
        msg[3] = (printLength and 0xFF).toByte()
        return msg
    }

    fun printAck(printLength: Int): ByteArray {
        val msg = ByteArray(6)
        msg[0] = MSG_PREFIX
        msg[1] = MSG_PRINT
        msg[2] = (printLength shr 8).toByte()
        msg[3] = (printLength and 0xFF).toByte()
        msg[4] = 0x01
        msg[5] = 0x00
        return msg
    }

    fun isPrintComplete(msg: ByteArray): Boolean =
        msg.size >= 2 && msg[0] == MSG_PREFIX && msg[1] == MSG_PRINT_COMPLETE

    fun parsePrintCompleteLength(msg: ByteArray): Int =
        ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
}
