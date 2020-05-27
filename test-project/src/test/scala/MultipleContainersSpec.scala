import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.scalatest.TestContainersForAll
import org.scalatest._

import scala.io.Source

class MultipleContainersSpec extends WordSpec with MustMatchers with TestContainersForAll {

  override type Containers = HelloJava and SampleJava

  override def startContainers(): Containers = {
    HelloJava.Def().start() and SampleJava.Def().start()
  }

  "container" must {
    "respond to an http request" in withContainers { case helloJava and sampleJava =>
      val helloJavaInputStream = Source.fromInputStream(helloJava.rootUrl.openConnection().getInputStream)
      helloJavaInputStream.mkString must equal ("hello, world")

      val sampleJavaInputStream = Source.fromInputStream(sampleJava.rootUrl.openConnection().getInputStream)
      sampleJavaInputStream.mkString must equal ("hello, world")
    }
  }

}
