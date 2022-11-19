import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

interface SslWrapper {
    fun wrap(buffer: ByteBuffer): ByteBuffer
    fun unwrap(inputStream: NioInputStream): NioInputStream
}

val threadPool: ExecutorService = Executors.newCachedThreadPool()

fun Executor.executeTask(taskName: String, task: () -> Unit) =
    execute { println("task(${taskName}):${measureTimeMillis { task() }}") }


suspend fun nioHandShake(
    url: URL,
    inputStream: NioInputStream,
    outputStream: NioOutputStream
): SslWrapper {
    val engine = SSLContext.getDefault()
        .createSSLEngine(url.host, url.defaultPort).apply { useClientMode = true }
    engine.beginHandshake()

    val myAppData = ByteBuffer.allocate(0) //握手阶段不需要src数据
    var myNetData = ByteBuffer.allocate(BUF_SIZE)
    var peerAppData = ByteBuffer.allocate(BUF_SIZE)
    var peerNetData = ByteBuffer.allocate(BUF_SIZE).apply { flip() }

    var hs = engine.handshakeStatus
//    println(hs)
    while (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
        hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    ) {
        when (hs) {
            SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                var result = engine.unwrap(peerNetData, peerAppData)
                while (result.status != SSLEngineResult.Status.OK) {
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            peerNetData.compact()
                            if (!peerNetData.hasRemaining()) {
                                peerNetData = peerNetData.enlargeCapacity()
                            }
                            inputStream.read(peerNetData)
                            peerNetData.flip()
                            result = engine.unwrap(peerNetData, peerAppData)
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            peerAppData = peerAppData.enlargeCapacity()
                            result = engine.unwrap(peerNetData, peerAppData)
                        }
                    }
                }
                hs = result.handshakeStatus
            }
            SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                myNetData.clear()
                var res = engine.wrap(myAppData, myNetData)
                while (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    myNetData = myNetData.enlargeCapacity()
                    res = engine.wrap(myAppData, myNetData)
                }
                if (res.status == SSLEngineResult.Status.OK) {
                    myNetData.flip()
                    while (myNetData.hasRemaining()) {
                        outputStream.write(myNetData)
                    }
                }
                hs = res.handshakeStatus
            }
            SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                suspendCoroutine<Unit> {
                    threadPool.executeTask("delegatedTask") {
                        engine.delegatedTask?.run()
                        it.resumeWith(Result.success(Unit))
                    }
                }
                hs = engine.handshakeStatus
            }
        }
//        println(hs)
    }
    return object : SslWrapper {
        override fun wrap(buffer: ByteBuffer): ByteBuffer {
            var result = ByteBuffer.allocate(BUF_SIZE)
            var status = engine.wrap(buffer, result).status
            while (status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                result = result.enlargeCapacity()
                status = engine.wrap(buffer, result).status
            }
            result.flip()
            return result
        }

        override fun unwrap(inputStream: NioInputStream): NioInputStream {
            var netData = ByteBuffer.allocate(BUF_SIZE).apply { flip() }
            var appData = ByteBuffer.allocate(BUF_SIZE).apply { flip() }
            return nioInputStream({ inputStream.close() }) { buffer ->
                if (appData.hasRemaining()) {
                    return@nioInputStream buffer.safePut(appData)
                }
                appData.clear()
                var res = engine.unwrap(netData, appData)


                while (res.status != SSLEngineResult.Status.OK) {
                    when (res.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            netData.compact()
                            if (!netData.hasRemaining()) {
                                netData = netData.enlargeCapacity()
                            }
                            inputStream.read(netData)
                            netData.flip()
                            res = engine.unwrap(netData, appData)
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appData = appData.enlargeCapacity()
                            res = engine.unwrap(netData, appData)
                        }
                    }
                }
                appData.flip()
                buffer.safePut(appData)
            }
        }
    }
}