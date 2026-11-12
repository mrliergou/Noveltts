package app.noveltts.engine.edge

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeGecTest {
    @Test
    fun matchesReferenceSha256Stage() {
        val method = EdgeGec::class.java.getDeclaredMethod(
            "sha256Digest",
            ByteArray::class.java,
        )
        method.isAccessible = true
        val data = "116444736000000000${EdgeGec.TRUSTED_CLIENT_TOKEN}"
            .toByteArray(Charsets.US_ASCII)
        val digest = method.invoke(EdgeGec, data) as ByteArray
        assertEquals(
            "28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) },
        )
    }

    @Test
    fun matchesReferenceCs64Stage() {
        val digestMethod = EdgeGec::class.java.getDeclaredMethod(
            "sha256Digest",
            ByteArray::class.java,
        ).apply { isAccessible = true }
        val cs64Method = EdgeGec::class.java.getDeclaredMethod(
            "cs64Digest",
            ByteArray::class.java,
            ByteArray::class.java,
        ).apply { isAccessible = true }
        val data = "116444736000000000${EdgeGec.TRUSTED_CLIENT_TOKEN}"
            .toByteArray(Charsets.US_ASCII)
        val digest = digestMethod.invoke(EdgeGec, data) as ByteArray
        val pair = cs64Method.invoke(EdgeGec, data, digest) as Pair<*, *>
        assertEquals(393_919_570, pair.first)
        assertEquals(3_388_870_069L, (pair.second as Int).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun matchesReferenceVectors() {
        assertEquals(
            "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            EdgeGec.token(0.0),
        )
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeGec.token(1_700_000_000.0),
        )
        assertEquals(
            "713C2BF431375CEC-2322C6B3E513D50276E09641CE873C8914A93295C8DDA6121A6CBE99EDD41784",
            EdgeGec.token(1_762_500_000.5),
        )
    }
}
