package app.noveltts.rules

import java.io.*

/** Private descriptors only; never accepts a network or exported file deserialization source. */
internal object RuleWire {
    const val MAX_BYTES = 32L * 1024 * 1024
    fun write(output: OutputStream, value: Any) {
        ObjectOutputStream(object : FilterOutputStream(output) {
            private var bytes = 0L
            private fun count(size: Int) { require(size >= 0 && size <= MAX_BYTES - bytes) { "规则传输超过上限" }; bytes += size }
            override fun write(b: Int) { count(1); out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { count(len); out.write(b, off, len) }
        }).use { it.writeObject(value) }
    }
    fun read(input: InputStream): Any = object : ObjectInputStream(object : FilterInputStream(input) {
        private var bytes = 0L
        private fun count(size: Int) { if (size > 0) { bytes += size; require(bytes <= MAX_BYTES) { "规则传输超过上限" } } }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { count(it) }
    }) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            require(desc.name.startsWith("app.noveltts.domain.") || desc.name.startsWith("java.lang.") ||
                desc.name.startsWith("java.util.") || desc.name.startsWith("kotlin.collections.") ||
                desc.name in setOf("[Ljava.lang.Object;", "[Ljava.lang.String;")) { "Invalid rule wire class" }
            return super.resolveClass(desc)
        }
        override fun resolveProxyClass(interfaces: Array<out String>): Class<*> = throw InvalidClassException("Proxy forbidden")
    }.use { it.readObject() }
}