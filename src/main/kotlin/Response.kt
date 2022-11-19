import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

enum class Protocol(private val protocol: String) {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1");

    companion object {
        /**
         * Returns the protocol identified by `protocol`.
         *
         * @throws IOException if `protocol` is unknown.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun get(protocol: String): Protocol {
            return when (protocol) {
                HTTP_1_0.protocol -> HTTP_1_0
                HTTP_1_1.protocol -> HTTP_1_1
                else -> throw IOException("Unexpected protocol: $protocol")
            }
        }
    }
}


operator fun List<Pair<String, String>>.get(name: String) = find { it.first == name }?.second

class Response @Throws(Exception::class) constructor(
    raw: String,
    inputStream: NioInputStream
) {
    val protocol: Protocol
    val code: Int
    val message: String
    val headers: List<Pair<String, String>>

    init {
        val lines = raw.split("\r\n")
        if (lines.size < 3) throw IOException()
        if (lines.last().isNotEmpty()) throw IOException()
        if (lines[lines.lastIndex - 1].isNotEmpty()) throw IOException()
        val statusLine = lines[0].split(" ", limit = 3)
        if (statusLine.size != 3) throw IOException()
        protocol = Protocol.get(statusLine[0])
        code = statusLine[1].toInt()
        message = statusLine[2]
        headers = lines
            .drop(1) //drop status line
            .dropLast(2)//drop empty string divided by  \r\n{empty}\r\n{empty}
            .map { line ->
                line.split(":", limit = 2).let { kv ->
                    if (kv.size != 2) throw IOException()
                    kv[0] to kv[1]
                }
            }
    }

    private val bodyReader by lazy {
        if (headers["Transfer-Encoding"]?.contains("chunked") == true) {
            return@lazy inputStream.chunkedBodyReader()
        }
        val contentLength = headers["Content-Length"]?.trim()?.toInt()
        if (contentLength != null) {
            return@lazy inputStream.fixedLengthReader(contentLength)
        }
        return@lazy inputStream
    }

    suspend fun bodyAsString(): String {
        var buffer = ByteBuffer.allocate(BUF_SIZE)
        bodyReader.use {
            while (it.read(buffer) >= 0) {
                if (!buffer.hasRemaining()) {
                    buffer = buffer.enlargeCapacity()
                }
            }
        }
        buffer.flip()
        return buffer.getString()
    }


    suspend fun bodyAsFile(): File {
        val file = File("test")
        val buffer = ByteBuffer.allocate(BUF_SIZE)

        file.outputStream().use { output ->
            bodyReader.use { input ->
                while (input.read(buffer) >= 0) {
                    output.write(buffer.array(), 0, buffer.position())
                    buffer.clear()
                }
            }
        }
        return file
    }


    class Builder(private val inputStream: NioInputStream) {
        suspend fun build(): Response {
            val result = inputStream.readStringEndWithDivider("\r\n\r\n".toByteArray(), true)
            return Response(result, inputStream)
        }
    }
}

fun ByteBuffer.getString(limit: Int = limit()) =
    String(array(), position(), limit - position()).also { position(limit) }

