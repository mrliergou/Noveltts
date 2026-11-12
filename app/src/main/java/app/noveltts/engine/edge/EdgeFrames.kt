package app.noveltts.engine.edge

/**
 * Edge Read Aloud 的 WebSocket 二进制帧解析。
 *
 * 帧布局固定为：`[2 字节大端 header 长度][header][payload]`，
 * header 是若干 `Key:Value\r\n` 行，末尾只有一个 CRLF，payload 紧接其后。
 *
 * 注意：早期实现按 `\r\n\r\n` 找 header 结尾，再 `+4` 作为 payload 起点，
 * 对这类帧会多跳 4 字节——正好吃掉每个 MP3 帧的同步头 `FF F3 64 C4`，
 * 解码出来就是"电音/杂声"。**任何改动都要先看这个类的单测。**
 */
internal object EdgeFrames {

    class Frame(val header: String, val payload: ByteArray)

    fun parse(frame: ByteArray): Frame? {
        if (frame.size < 4) return null
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val payloadStart = 2 + headerLength
        if (payloadStart > frame.size) return null
        val header = String(frame, 2, headerLength, Charsets.ISO_8859_1)
        return Frame(header, frame.copyOfRange(payloadStart, frame.size))
    }
}
