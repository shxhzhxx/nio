
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.min

interface RequestBody {
    val contentLength: Int
    val contentType: String
    val dataOutputStream: NioOutputStream
}

fun String.toRequestBody(contentType: String) = object : RequestBody {
    private val data = ByteBuffer.wrap(toByteArray())

    override val contentLength: Int
        get() = length
    override val contentType: String
        get() = contentType
    override val dataOutputStream: NioOutputStream
        get() = nioOutputStream{ buffer ->
            data.limit(data.capacity())
            buffer.safePut(data)
        }
}

fun ByteBuffer.safePut(src: ByteBuffer) = min(remaining(), src.remaining()).also { len ->
    put(src.array(), src.position(), len)
    src.position(src.position() + len)
}

class Request internal constructor(
    val url: URL,
    private val method: String,
    private val headers: List<Pair<String, String>>,
    private val body: RequestBody?
) {
    val isHttps get() = "https" == url.protocol
    private val requestHeader = ByteBuffer.wrap(kotlin.run {
        val path = url.file.let { if (it.isNullOrEmpty()) "/" else it }
        val headers = headers.joinToString(
            separator = "\r\n",
            postfix = "\r\n"
        ) { "${it.first}: ${it.second}" }
        return@run "$method $path HTTP/1.1\r\n$headers\r\n"
    }.toByteArray())

    suspend fun requestPacket(buffer: ByteBuffer): Int {
        requestHeader.limit(requestHeader.capacity())
        return when {
            requestHeader.hasRemaining() -> buffer.safePut(requestHeader)
            body != null -> body.dataOutputStream.write(buffer)
            else -> 0
        }
    }

    class Builder(url: String) {
        private val url = URL(url)
        private var method = "GET"
        private val headers = mutableListOf<Pair<String, String>>()
        private var body: RequestBody? = null

        fun post(body: RequestBody) = apply {
            method = "POST"
            this.body = body
        }

        fun addHeader(name: String, value: String) = apply {
            headers.add(name to value)
        }

        fun build(): Request {
            headers.add("Host" to url.host)
            headers.add("Connection" to "close") //keep-alive; close
            body?.also {
                headers.add("Content-Type" to it.contentType)
                headers.add("Content-Length" to it.contentLength.toString())
            }
            return Request(url, method, headers, body)
        }
    }
}