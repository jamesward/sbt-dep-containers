package com.jamesward.sbtdepcontainers

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import com.github.dockerjava.api.DockerClient
import com.jamesward.sbtdepcontainers.SbtDepContainersPlugin.ContainerID
import org.scalatest.{MustMatchers, WordSpec}
import org.slf4j.LoggerFactory
import sbt.util.{Level, Logger}

import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

class SbtDepContainersPluginSpec extends WordSpec with MustMatchers {

  lazy implicit val dockerClient: DockerClient = SbtDepContainersPlugin.createDockerClient

  def dockerRm(containerID: ContainerID): Unit = {
    Try {
      dockerClient.removeImageCmd(containerID.dockerTag).exec()
    }
  }

  def dockerStartStop(containerID: ContainerID): Unit = {
    SbtDepContainersPlugin.containerIDStart(containerID, logger)
    SbtDepContainersPlugin.containerIDStop(containerID, logger)
  }

  val logger: Logger = new Logger {
    val underlying = LoggerFactory.getLogger(getClass)
    override def trace(t: => Throwable): Unit = underlying.error("error", t)
    override def success(message: => String): Unit = underlying.info(message)
    override def log(level: Level.Value, message: => String): Unit = {
      level match {
        case Level.Debug => underlying.debug(message)
        case Level.Info => underlying.info(message)
        case Level.Warn => underlying.warn(message)
        case Level.Error => underlying.error(message)
      }
    }
  }

  def createTmpDir(containerID: ContainerID): File = Files.createTempDirectory(containerID.name).toFile

  def createContainer(containerID: ContainerID, tmpDir: File): Unit = {
    SbtDepContainersPlugin.createContainer(tmpDir, SbtDepContainersPlugin.defaultContainerBuilder, logger)(containerID)
  }

  def createContainer(containerID: ContainerID): Unit = {
    val tmpDir = createTmpDir(containerID)
    createContainer(containerID, tmpDir)
  }

  "containerID" must {
    "have correct package and class names" in {
      ContainerID(new URL("https://github.com/jamesward/foo-bar.git"), "master") must have (
        'packageName ("com.github.jamesward"),
        'className ("DepFooBar"),
      )

      ContainerID(new URL("https://github.com/jamesward/something.git"), "master", Some("foo/bar")) must have (
        'packageName ("com.github.jamesward.something.foo"),
        'className ("DepBar"),
      )
    }
  }

  "createContainer" must {
    "work" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "master")
      dockerRm(containerID)
      createContainer(containerID)
      dockerStartStop(containerID)
    }
  }

  "createContainer" must {
    "not create if nothing has changed" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "master")
      val tmpDir = createTmpDir(containerID)
      dockerRm(containerID)
      createContainer(containerID, tmpDir)
      val startTime = System.nanoTime()
      createContainer(containerID, tmpDir)
      val duration = FiniteDuration(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
      // todo: it is bad to do this based on timing
      (duration < 2.seconds) must be (true)
    }
  }

  "createContainer" must {
    "fail with something not buildable by buildpacks" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-netcat.git"), "master")
      dockerRm(containerID)
      an[Exception] must be thrownBy createContainer(containerID)
    }
  }

  "createContainer" must {
    "work with a subdir" in {
      val containerID = ContainerID(new URL("https://github.com/GoogleCloudPlatform/buildpack-samples.git"), "master", Some("sample-java-mvn"))
      dockerRm(containerID)
      createContainer(containerID)
    }
  }

  "createContainer" must {
    "work with a git tag" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "v0.0.1")
      dockerRm(containerID)
      createContainer(containerID)
    }
  }

  "containersStartAll" must {
    "start a containerID" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "master")
      val url = SbtDepContainersPlugin.containerIDsStart(Seq(containerID), logger)(containerID)
      val is = Source.fromInputStream(url.openConnection().getInputStream)
      is.mkString must equal ("hello, world")
    }
  }

  "containerIDStart" must {
    "not start more than one" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "master")
      val firstUrl = SbtDepContainersPlugin.containerIDStart(containerID, logger)
      val secondUrl = SbtDepContainersPlugin.containerIDStart(containerID, logger)

      // toString to avoid the DNS comparison
      secondUrl.toString must equal (firstUrl.toString)

      SbtDepContainersPlugin.containerIDStop(containerID, logger)
    }
  }

  "containersStopAll" must {
    "stop a containerID" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "master")
      SbtDepContainersPlugin.containersStopAll(Seq(containerID), Set.empty, logger)
    }
  }

  // todo: test starting a TestContainer but right now that happens in a sbt task

}
