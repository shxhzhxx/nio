import java.nio.ByteBuffer


val BUF_SIZE get() = (/*Math.random() **/ 1024 + 1).toInt()

class NioHttpClient {
    private val core = NioCore()

    suspend fun request(request: Request) =
        if (request.isHttps) requestHttpsInternal(request) else requestHttpInternal(request)

    private suspend fun requestHttpInternal(request: Request): Response {
        val channel = core.connect(request.url)
        val buffer = ByteBuffer.allocate(BUF_SIZE)
        while (request.requestPacket(buffer) > 0) {
            buffer.flip()
            core.write(channel, buffer)
            buffer.clear()
        }
        return Response.Builder(nioInputStream({ channel.close() }) { core.read(channel, it) }).build()
    }

    private suspend fun requestHttpsInternal(request: Request): Response {
        val channel = core.connect(request.url)
        val buffer = ByteBuffer.allocate(BUF_SIZE)
        val inputStream = nioInputStream({ channel.close() }) { core.read(channel, it) }
        val sslWrapper = nioHandShake(request.url, inputStream, nioOutputStream { core.write(channel, it) })
        while (request.requestPacket(buffer) > 0) {
            buffer.flip()
            core.write(channel, sslWrapper.wrap(buffer))
            buffer.clear()
        }
        return Response.Builder(sslWrapper.unwrap(inputStream)).build()
    }
}
