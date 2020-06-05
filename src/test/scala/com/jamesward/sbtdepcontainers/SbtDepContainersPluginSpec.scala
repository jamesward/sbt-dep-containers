package com.jamesward.sbtdepcontainers

import java.net.URL
import java.nio.file.Files

import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder}
import com.jamesward.sbtdepcontainers.SbtDepContainersPlugin.ContainerID
import org.scalatest.{MustMatchers, WordSpec}
import org.slf4j.LoggerFactory
import sbt.librarymanagement.ModuleID
import sbt.util.{Level, Logger}

import scala.io.Source
import scala.util.Try

class SbtDepContainersPluginSpec extends WordSpec with MustMatchers {

  def dockerRm(containerID: ContainerID): Unit = {
    val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build()
    val dockerClient = DockerClientBuilder.getInstance(config).build()
    Try {
      dockerClient.removeImageCmd(containerID.dockerTag).exec()
    }
  }

  def dockerStartStop(containerID: ContainerID): Unit = {
    val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build()
    val dockerClient = DockerClientBuilder.getInstance(config).build()
    val container = dockerClient.createContainerCmd(containerID.dockerTag).exec()
    dockerClient.startContainerCmd(container.getId).exec()
    dockerClient.stopContainerCmd(container.getId).exec()
  }

  val logger = new Logger {
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

  def createContainer(containerID: ContainerID): Unit = {
    SbtDepContainersPlugin.createContainer(Files.createTempDirectory(containerID.name).toFile, SbtDepContainersPlugin.defaultContainerBuilder, logger)(containerID)
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

  "containersStopAll" must {
    "stop a containerID" in {
      val containerID = ContainerID(new URL("https://github.com/jamesward/hello-java.git"), "master")
      SbtDepContainersPlugin.containersStopAll(Seq(containerID), Seq.empty, logger)
    }
  }

  // todo: test starting a TestContainer but right now that happens in a sbt task

}
