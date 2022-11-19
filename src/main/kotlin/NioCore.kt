import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.suspendCoroutine

private class RegInfo(val channel: SocketChannel, val ops: Int)

private infix fun SocketChannel.to(that: Int): RegInfo =
    RegInfo(this, that)

private class NioRequest(
    val regInfo: () -> RegInfo,
    val onResult: (SocketChannel) -> Unit
)

class NioCore {
    private val executor by lazy { Executors.newSingleThreadExecutor() }
    private val requestQueue by lazy { LinkedBlockingQueue<NioRequest>() }
    private val selector by lazy {
        Selector.open().also { selector ->
            executor.execute {
                while (!isShutdown) {
                    while (requestQueue.isNotEmpty()) {
                        val request = requestQueue.poll() ?: continue
                        val regInfo = request.regInfo()
                        regInfo.channel.register(selector, regInfo.ops, request)
                    }
                    while (selector.select() > 0) {
                        selector.selectedKeys().forEach { key ->
                            (key.attachment() as NioRequest).onResult(key.channel() as SocketChannel)
                            key.cancel()
                        }
                    }
                }
            }
        }
    }

    val isShutdown get() = executor.isShutdown

    fun shutdown() {
        executor.shutdownNow()
        selector.close()
    }

    suspend fun read(channel: SocketChannel, buffer: ByteBuffer): Int {
        return suspendCoroutine {
            requestQueue.add(NioRequest(
                regInfo = {
                    channel to SelectionKey.OP_READ
                }, onResult = { channel ->
                    it.resumeWith(Result.success(channel.read(buffer)))
                }
            ))
            selector.wakeup()
        }
    }

    suspend fun write(channel: SocketChannel, buffer: ByteBuffer): Int {
        return suspendCoroutine {
            requestQueue.add(NioRequest(
                regInfo = {
                    channel to SelectionKey.OP_WRITE
                }, onResult = { channel ->
                    it.resumeWith(Result.success(channel.write(buffer)))
                }
            ))
            selector.wakeup()
        }
    }

    suspend fun connect(url: URL): SocketChannel {
        val address = suspendCoroutine<SocketAddress> {
            threadPool.executeTask("dns"){
                val address = InetSocketAddress(url.host, url.defaultPort)
                it.resumeWith(Result.success(address))
            }
        }
        return suspendCoroutine {
            requestQueue.add(
                NioRequest(
                    regInfo = {
                        val channel = SocketChannel.open()
                        channel.configureBlocking(false)
                        channel.connect(address)
                        channel to SelectionKey.OP_CONNECT
                    }, onResult = { channel ->
                        try {
                            if (channel.finishConnect()) {
                                it.resumeWith(Result.success(channel))
                            } else {
                                //connection process is not yet complete
                            }
                        } catch (e: IOException) {
                            channel.close()
                            it.resumeWith(Result.failure(e))
                        }
                    })
            )
            selector.wakeup()
        }
    }
}
