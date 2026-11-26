package com.example

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** FMC ABI 1.4 K-channel wire implementation, isolated from WatchLink's legacy session. */
internal interface FmcGattLink {
    suspend fun write(uuid: String, bytes: ByteArray)
    fun onNotify(handler: (String, ByteArray) -> Unit)
    fun onDropped(handler: () -> Unit) {}
}

internal const val FMC_CMD_WRITE = "0000FFF2-0000-1000-8000-00805F9B34FB"
internal const val FMC_CMD_NOTIFY = "0000FFF1-0000-1000-8000-00805F9B34FB"
internal const val FMC_SHELL_WRITE = "77D4FF01-2FE2-2334-0D35-9CCD078F529C"
internal const val FMC_SHELL_NOTIFY = "77D4FF02-2FE2-2334-0D35-9CCD078F529C"
private const val FMC_SYSTEM = 0xFFFF
private const val FMC_MSG = 0x90A5
private const val FMC_PING = 0x90A7
private const val FMC_EVENT = 0xB0A6

private val fmcIv = byteArrayOf(
    0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
    0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x5A
)

private fun fmcCrc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

private fun fmcCrcLe(data: ByteArray): ByteArray {
    val v = fmcCrc32(data).toInt()
    return byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
}

private fun fmcSeal(key: ByteArray, plain: ByteArray): ByteArray {
    require(key.size == 16)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fmcIv))
    return cipher.doFinal(plain + fmcCrcLe(plain))
}

private fun fmcOpen(key: ByteArray, encrypted: ByteArray): ByteArray? {
    if (key.size != 16 || encrypted.isEmpty() || encrypted.size % 16 != 0) return null
    return try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fmcIv))
        val decoded = cipher.doFinal(encrypted)
        if (decoded.size < 4) return null
        val plain = decoded.copyOf(decoded.size - 4)
        val crc = decoded.copyOfRange(decoded.size - 4, decoded.size)
        if (!crc.contentEquals(fmcCrcLe(plain))) null else plain
    } catch (_: Exception) {
        null
    }
}

private fun fmcFrame(c2: Int, payload: ByteArray, key: ByteArray): ByteArray {
    val body = fmcSeal(key, payload)
    val header = byteArrayOf(
        0xF5.toByte(),
        (body.size ushr 8).toByte(), body.size.toByte(),
        0xFF.toByte(), 0xFF.toByte(),
        0x00, 0x01,
        0x00, 0x01,
        (c2 ushr 8).toByte(), c2.toByte()
    )
    return header + body
}

private data class FmcRawFrame(val c1: Int, val c2: Int, val body: ByteArray)

private class FmcFrameParser {
    private var buffer = ByteArray(0)

    @Synchronized
    fun feed(bytes: ByteArray): List<FmcRawFrame> {
        buffer += bytes
        val output = mutableListOf<FmcRawFrame>()
        while (true) {
            val start = buffer.indexOf(0xF5.toByte())
            if (start < 0) {
                buffer = ByteArray(0)
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < 11) break
            val length = ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
            val c1 = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
            val c2 = ((buffer[9].toInt() and 0xFF) shl 8) or (buffer[10].toInt() and 0xFF)
            if (length < 0 || length > 4096) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < 11 + length) break
            output += FmcRawFrame(c1, c2, buffer.copyOfRange(11, 11 + length))
            buffer = buffer.copyOfRange(11 + length, buffer.size)
        }
        return output
    }
}

internal class FmcKSession private constructor(
    private val link: FmcGattLink,
    private val key: ByteArray
) {
    private val parser = FmcFrameParser()
    private val frames = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
    private val events = Channel<ByteArray>(Channel.UNLIMITED)
    private val requestMutex = Mutex()

    companion object {
        suspend fun open(link: FmcGattLink): FmcKSession {
            val shell = Channel<ByteArray>(Channel.UNLIMITED)
            val ref = AtomicReference<FmcKSession?>(null)
            link.onNotify { uuid, value ->
                when (uuid.uppercase()) {
                    FMC_SHELL_NOTIFY -> shell.trySend(value)
                    FMC_CMD_NOTIFY -> ref.get()?.feed(value)
                }
            }

            var secret: String? = null
            for (attempt in 0 until 3) {
                if (secret != null) break
                link.write(FMC_SHELL_WRITE, "AT GETSECRET\r\n".toByteArray(Charsets.US_ASCII))
                val reply = StringBuilder()
                val deadline = System.currentTimeMillis() + 6_000L
                while (System.currentTimeMillis() < deadline && !reply.contains(",OK") &&
                    !reply.contains(",ERR") && !reply.contains(",FAIL")) {
                    val left = deadline - System.currentTimeMillis()
                    val part = withTimeoutOrNull(left.coerceAtLeast(1L)) { shell.receive() } ?: break
                    reply.append(String(part, Charsets.UTF_8))
                }
                val match = Regex("GETSECRET:([0-9A-Fa-f]{32}),OK").find(reply.toString())
                secret = match?.groupValues?.get(1)
            }
            val secretBytes = secret?.let { runCatching { ByteArray(16) { i -> it.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull() }
                ?: throw IllegalStateException("The watch did not return its K channel secret. Check that Nothing X is paired and the watch firmware supports the FMC channel.")

            val session = FmcKSession(link, secretBytes)
            ref.set(session)
            return session
        }
    }

    @Synchronized
    private fun feed(bytes: ByteArray) {
        parser.feed(bytes).forEach { frame ->
            if (frame.c1 != FMC_SYSTEM) return@forEach
            if (frame.c2 == FMC_EVENT) events.trySend(frame.body)
            else frames.trySend(frame.c2 to frame.body)
        }
    }

    private suspend fun request(opcode: Int, payload: ByteArray, timeoutMs: Long = 6_000L): ByteArray =
        requestMutex.withLock {
            while (frames.tryReceive().isSuccess) { /* discard stale replies */ }
            link.write(FMC_CMD_WRITE, fmcFrame(opcode, payload, key))
            val deadline = System.currentTimeMillis() + timeoutMs
            var response: ByteArray? = null
            while (response == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("Timed out waiting for watch reply to %04X".format(opcode))
                val frame = withTimeoutOrNull(left) { frames.receive() }
                    ?: throw IllegalStateException("Timed out waiting for watch reply to %04X".format(opcode))
                if (frame.first == (opcode or 0xA000)) {
                    response = fmcOpen(key, frame.second)
                }
                // Frames sent by Nothing X are visible to every GATT client but do not decrypt with K.
            }
            response
        }

    suspend fun ping(): Pair<Int, Int> {
        val reply = request(FMC_PING, byteArrayOf(0))
        if (reply.size != 3 || (reply[0].toInt() and 0xFF) != 0) {
            throw IllegalStateException("The watch rejected the FMC app-channel PING")
        }
        return (reply[1].toInt() and 0xFF) to (reply[2].toInt() and 0xFF)
    }

    suspend fun send(data: ByteArray) {
        require(data.isNotEmpty() && data.size <= 128) { "Watch app messages must be 1–128 bytes" }
        val deadline = System.currentTimeMillis() + 10_000L
        var pause = 20L
        while (true) {
            val reply = request(FMC_MSG, byteArrayOf(0xFF.toByte()) + data)
            val status = reply.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF
            when (status) {
                0 -> return
                7 -> throw IllegalStateException("No compatible watch app is currently running")
                8, 9 -> {
                    if (System.currentTimeMillis() + pause >= deadline) {
                        throw IllegalStateException("The watch app inbox stayed busy or paused")
                    }
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(500L)
                }
                else -> throw IllegalStateException("The watch rejected the app message (status $status)")
            }
        }
    }

    /** Return EVENT's original sequence, originating slot and app payload. */
    suspend fun receive(): Triple<Int, Int, ByteArray> {
        while (true) {
            val body = events.receive()
            val plain = fmcOpen(key, body)
            if (plain != null && plain.size >= 3) {
                return Triple(
                    plain[0].toInt() and 0xFF,
                    plain[1].toInt() and 0xFF,
                    plain.copyOfRange(2, plain.size)
                )
            }
        }
    }
}
