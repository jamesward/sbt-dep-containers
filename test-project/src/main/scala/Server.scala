import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URL}

import scala.io.Source
import scala.util.Using

object Server {

  def main(args: Array[String]): Unit = {
    val port = sys.env.getOrElse("PORT", "8080").toInt

    val helloJavaUrl = new URL(sys.env("HELLO_JAVA_URL"))
    val sampleJavaUrl = new URL(sys.env("SAMPLE_JAVA_MVN_URL"))

    val server = HttpServer.create(new InetSocketAddress(port), 0)

    server.createContext("/").setHandler((exchange: HttpExchange) => {
      val helloJavaResponse = Using(helloJavaUrl.openConnection().getInputStream)(Source.fromInputStream(_).mkString).get
      val sampleJavaResponse = Using(sampleJavaUrl.openConnection().getInputStream)(Source.fromInputStream(_).mkString).get

      val response =s"""
          |helloJava: $helloJavaResponse
          |sampleJava: $sampleJavaResponse
          |""".stripMargin.getBytes

      exchange.sendResponseHeaders(200, response.length)
      Using(exchange.getResponseBody)(_.write(response))
    })

    println("Listening at http://localhost:" + port)

    server.start()
  }

}
