import kotlinx.coroutines.*
import java.net.Socket
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

fun main() {
    runBlocking {
        launch {
//            while (true){
//                debug()
//            }

            test()
        }

    }

}


val client = NioHttpClient()
private val urlPattern by lazy { Pattern.compile("\"thumbURL\":\"([^\"]+)\"") }
private suspend fun imgUrls(key: String): List<String> {
    val response = client.request(
        Request.Builder("https://image.baidu.com/search/index?tn=baiduimage&ps=1&ct=201326592&lm=-1&cl=2&nc=1&ie=utf-8&word=$key")
            .build()
    ).bodyAsString()

    val result = mutableListOf<String>()
    val matcher = urlPattern.matcher(response)
    while (matcher.find()) {
        matcher.group(1)?.also {
            result.add(it.replace("\\/", "/"))
        }
    }
    return result
}


private suspend fun debug() {
    val urls = mutableListOf<String>(
        "https://www.baidu.com",
        "https://www.bilibili.com/",
        "https://www.qq.com/",
        "https://www.taobao.com/",
        "https://www.toutiao.com/",
        "https://www.pinduoduo.com/",
        "http://json.cn/"
    )
    urls.addAll(imgUrls("风景"))


//    kotlin.io.println("start")
//    for (url in urls){
//        println("start:$url")
//        val response = client.request(Request.Builder(url).build())
//        response.bodyAsString()
//        println("end:$url")
//    }

    val tasks = urls.map { url ->
        GlobalScope.async {
            measureTimeMillis {
                try {
                    val response = client.request(Request.Builder(url).build())
                    response.bodyAsString()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

            }
        }
    }
    val results = tasks.map { it.await() }
    val ave = results.average()
    val max = results.max()
    val min = results.min()

    kotlin.io.println("result:$results")
}

private suspend fun test() {
    val request = Request.Builder("http://api.1sapp.com/operate/resource/openScreen")
        .addHeader("User-Agent", "qukan_android")
        .addHeader("Accept-Language", "zh-CN,zh-CN;q=0.8,en-US;q=0.6")
        .post(
            "OSVersion=8.0.0&brand=HONOR&client_version=30949000&device=866369036051342&deviceCode=866369036051342&device_code=866369036051342&distinct_id=dcaba0254364fa0d&dtu=004&guid=296cd595733325db155f5634173.92375903&is_pure=0&lat=0.0&lon=0.0&manufacturer=HUAWEI&model=BND-AL10&network=wifi&oaid=fa775dde-afff-0fb0-7ec7-d7fbffb7954a&time=1571975882587&tk=ACGnL6Aum3Uw5iFHC_tAJt40P-Kxluy7R700NzUxNDk1MDg5NTIyNQ&traceId=492cd7c78e2980f190edd3880c61ecfc&tuid=py-gLpt1MOYhRwv7QCbeNA&uuid=0c4b7d50be0c4e5891b2d0c3c9dbcb0d&version=30949000&versionName=3.9.49.000-debug&sign=3b685652d02f8f4f51bac902929e1aa3".toRequestBody(
                "application/x-www-form-urlencoded"
            )
        )
        .build()
    var response = client.request(request)
    kotlin.io.println("header:" + response.headers)
    kotlin.io.println("body:" + response.bodyAsString())

    kotlin.io.println("request")
    response = client.request(
        Request
//                    .Builder("https://cdn.pixabay.com/photo/2015/06/19/21/24/the-road-815297_1280.jpg")
            .Builder("https://cn.vuejs.org/")
            .build()
    )

    kotlin.io.println("protocol:${response.protocol}")
    kotlin.io.println("code:${response.code}")
    kotlin.io.println("message:${response.message}")
    kotlin.io.println("header:" + response.headers)

    kotlin.io.println("body:" + response.bodyAsString())


//            val file = response.bodyAsFile(this@MainActivity)
//            println("download complete")
//
//            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
//            image.setImageBitmap(bitmap)
}