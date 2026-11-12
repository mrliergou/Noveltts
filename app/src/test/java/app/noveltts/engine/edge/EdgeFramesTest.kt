package app.noveltts.engine.edge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeFramesTest {

    private fun frame(header: String, payload: ByteArray): ByteArray {
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)
        val length = headerBytes.size
        val out = ByteArray(2 + headerBytes.size + payload.size)
        out[0] = ((length shr 8) and 0xFF).toByte()
        out[1] = (length and 0xFF).toByte()
        headerBytes.copyInto(out, 2)
        payload.copyInto(out, 2 + headerBytes.size)
        return out
    }

    private val audioHeader =
        "X-RequestId:ABC\r\nContent-Type:audio/mpeg\r\nX-StreamId:DEF\r\nPath:audio\r\n"

    @Test
    fun `payload starts right after declared header length`() {
        val payload = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x64, 0xC4.toByte(), 0x00, 0x00, 0x00, 0x03)
        val parsed = EdgeFrames.parse(frame(audioHeader, payload))!!
        assertTrue(parsed.header.contains("Path:audio"))
        // 不能丢掉 MP3 帧同步头，否则解码出来是电音
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `header length is big endian`() {
        val payload = ByteArray(700) { it.toByte() }
        val parsed = EdgeFrames.parse(frame("Path:audio\r\n", payload))!!
        assertEquals(700, parsed.payload.size)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `payload may contain crlf sequences`() {
        val payload = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A, 0x41)
        val parsed = EdgeFrames.parse(frame(audioHeader, payload))!!
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `truncated frame is rejected`() {
        val header = "Path:audio\r\n"
        val bytes = frame(header, byteArrayOf(1, 2, 3))
        assertNull(EdgeFrames.parse(bytes.copyOfRange(0, 5)))
    }

    @Test
    fun `empty payload yields empty bytes`() {
        val parsed = EdgeFrames.parse(frame("Path:turn.start\r\n", ByteArray(0)))!!
        assertTrue(parsed.payload.isEmpty())
    }
}
