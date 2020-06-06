import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URI, URL}
import java.sql.DriverManager

import scala.io.Source
import scala.util.Using

object Server {

  def handleRequest(helloJavaUrl: URL, sampleJavaUrl: URL, dbUri: URI)(exchange: HttpExchange): Unit = {
    val Array(username, password) = dbUri.getUserInfo.split(":")
    val dbUrl = "jdbc:postgresql://" + dbUri.getHost + ":" + dbUri.getPort + dbUri.getPath

    Class.forName("org.postgresql.Driver")
    val cn = DriverManager.getConnection(dbUrl, username, password)
    val st = cn.createStatement
    val rs = st.executeQuery("SELECT 1")
    rs.next()
    val dbResult = rs.getString(1)

    val helloJavaResponse = Using(helloJavaUrl.openConnection().getInputStream)(Source.fromInputStream(_).mkString).get
    val sampleJavaResponse = Using(sampleJavaUrl.openConnection().getInputStream)(Source.fromInputStream(_).mkString).get

    val response =s"""
                     |zxcv
                     |helloJava: $helloJavaResponse
                     |sampleJava: $sampleJavaResponse
                     |dbResult: $dbResult
                     |""".stripMargin.getBytes

    exchange.sendResponseHeaders(200, response.length)
    Using(exchange.getResponseBody)(_.write(response))
  }

  def main(args: Array[String]): Unit = {
    val port = sys.env.getOrElse("PORT", "8080").toInt

    val helloJavaUrl = new URL(sys.env("HELLO_JAVA_URL"))
    val sampleJavaUrl = new URL(sys.env("SAMPLE_JAVA_MVN_URL"))
    val databaseUrl = new URI(sys.env("DATABASE_URL"))

    val server = HttpServer.create(new InetSocketAddress(port), 0)

    server.createContext("/").setHandler(handleRequest(helloJavaUrl, sampleJavaUrl, databaseUrl))

    println("Listening at http://localhost:" + port)

    server.start()
  }

}
