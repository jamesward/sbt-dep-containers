import java.io.{InputStream, OutputStream}
import java.net.URI

import org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import com.github.googlecloudplatform.buildpacksamples.DepSampleJavaMvn
import com.github.jamesward.DepHelloJava
import com.sun.net.httpserver.HttpExchange
import org.scalatest._


class ServerSpec extends WordSpec with MustMatchers with TestContainersForAll {

  override type Containers = DepHelloJava and DepSampleJavaMvn and PostgreSQLContainer

  override def startContainers() = {
    DepHelloJava.Def().start() and DepSampleJavaMvn.Def().start() and PostgreSQLContainer.Def().start()
  }

  "server" must {
    "handle a request" in withContainers { case helloJava and sampleJava and postgres =>
      var responseStatus = Option.empty[Int]

      val httpExchange = new HttpExchange {
        override def getRequestHeaders = ???
        override def getResponseHeaders = ???
        override def getRequestURI = ???
        override def getRequestMethod = ???
        override def getHttpContext = ???
        override def close() = ???
        override def getRequestBody = ???
        override def getResponseBody = ???
        override def sendResponseHeaders(rCode: Int, responseLength: Long) = { responseStatus = Some(rCode) }
        override def getRemoteAddress = ???
        override def getResponseCode = ???
        override def getLocalAddress = ???
        override def getProtocol = ???
        override def getAttribute(name: String) = ???
        override def setAttribute(name: String, value: Any) = ???
        override def setStreams(i: InputStream, o: OutputStream) = ???
        override def getPrincipal = ???
      }

      val dbUri = new URI(s"postgres://${postgres.username}:${postgres.password}@${postgres.containerIpAddress}:${postgres.mappedPort(POSTGRESQL_PORT)}/${postgres.databaseName}")

      Server.handleRequest(helloJava.rootUrl, sampleJava.rootUrl, dbUri)(httpExchange)

      responseStatus must contain (200)
    }
  }

}
