import java.io.Closeable
import java.nio.ByteBuffer
import java.time.LocalDateTime


interface NioOutputStream {
    suspend fun write(buffer: ByteBuffer): Int
}

fun nioOutputStream(writer: suspend (buffer: ByteBuffer) -> Int) = object : NioOutputStream {
    override suspend fun write(buffer: ByteBuffer): Int {
        return writer(buffer)
    }
}

interface NioInputStream : Closeable {
    suspend fun read(buffer: ByteBuffer): Int
    fun pad(buffer: ByteBuffer)
}

fun nioInputStream(onClose: () -> Unit, reader: suspend (buffer: ByteBuffer) -> Int) = object : NioInputStream {
    var cache = ByteBuffer.allocate(0)
    override suspend fun read(buffer: ByteBuffer): Int {
        if (cache.hasRemaining()) {
            return buffer.safePut(cache)
        }
        return reader(buffer)
    }

    override fun pad(buffer: ByteBuffer) {
        cache.compact()
        if (cache.remaining() < buffer.remaining()) {
            cache = cache.enlargeCapacity(buffer.remaining() - cache.remaining())
        }
        cache.flip()
        val len = cache.limit()
        cache.limit(len + buffer.remaining())
        System.arraycopy(cache.array(), 0, cache.array(), buffer.remaining(), len)
        cache.put(buffer)
        cache.position(0)
    }

    override fun close() {
        onClose()
    }
}


suspend fun NioInputStream.readStringEndWithDivider(divider: ByteArray, includeDivider: Boolean = false): String {
    var dividerIndex = 0
    var buffer = ByteBuffer.allocate(BUF_SIZE)
    var i = 0
    while (divider.size != dividerIndex) {
        if (!buffer.hasRemaining()) {
            buffer = buffer.enlargeCapacity()
        }
        if (read(buffer) < 0) {
            buffer.flip()
            return buffer.getString()
        }
        while (i < buffer.position() && divider.size != dividerIndex) {
            if (buffer.array()[i++] != divider[dividerIndex++]) {
                dividerIndex = 0
                continue
            }
        }
    }
    buffer.flip()
    val result = if (includeDivider) buffer.getString(i) else
        buffer.getString(i - divider.size).also { buffer.position(buffer.position() + divider.size) }
    pad(buffer)
    return result
}

fun NioInputStream.fixedLengthReader(length: Int): NioInputStream {
    var readLen = 0
    return nioInputStream({ close() }) { dst ->
        if (length == readLen) {
            return@nioInputStream -1
        }
        val originalLimit = dst.limit()
        if (length - readLen < dst.remaining()) {
            dst.limit(dst.position() + (length - readLen))
        }
        read(dst).also {
            readLen += it
            dst.limit(originalLimit)
        }
    }
}

fun NioInputStream.chunkedBodyReader(): NioInputStream {
    var reader: NioInputStream? = null
    return nioInputStream({ close() }) { dst ->
        reader = reader ?: fixedLengthReader(readStringEndWithDivider("\r\n".toByteArray()).toInt(16).also {
            if (it == 0) {
                readStringEndWithDivider("\r\n".toByteArray())
                return@nioInputStream -1
            }
        })
        val len = reader!!.read(dst)
        if (len < 0) {
            readStringEndWithDivider("\r\n".toByteArray())
            reader = null
            0
        } else len
    }
}


fun ByteBuffer.enlargeCapacity(increment: Int = capacity(), enlargeLimit: Boolean = true): ByteBuffer {
    val newBuffer = ByteBuffer.allocate(capacity() + increment)
    newBuffer.put(array())
    newBuffer.position(position())
    if (!enlargeLimit) {
        newBuffer.limit(limit())
    }
    newBuffer.order(order())
    return newBuffer
}

fun println(msg: Any) = kotlin.io.println("${LocalDateTime.now()} $msg")