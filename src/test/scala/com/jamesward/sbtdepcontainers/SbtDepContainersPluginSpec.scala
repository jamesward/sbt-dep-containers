package com.jamesward.sbtdepcontainers

import java.nio.file.Files

import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder}
import com.jamesward.sbtdepcontainers.SbtDepContainersPlugin.ContainerID
import org.scalatest.{MustMatchers, WordSpec}
import org.slf4j.LoggerFactory
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
    SbtDepContainersPlugin.createContainer(Files.createTempDirectory(containerID.validName).toFile, SbtDepContainersPlugin.defaultContainerBuilder, logger)(containerID)
  }

  "createContainer" must {
    "work" in {
      val containerID = ContainerID("test:hello-java", "https://github.com/jamesward/hello-java.git", "master")
      dockerRm(containerID)
      createContainer(containerID)
      dockerStartStop(containerID)
    }
  }

  "createContainer" must {
    "fail with something not buildable by buildpacks" in {
      val containerID = ContainerID("test-hello-netcat", "https://github.com/jamesward/hello-netcat.git", "master")
      dockerRm(containerID)
      an[Exception] must be thrownBy createContainer(containerID)
    }
  }

  "createContainer" must {
    "not fail when the name is invalid" in {
      val containerID = ContainerID("test:hello-java", "https://github.com/jamesward/hello-java.git", "master")
      dockerRm(containerID)
      createContainer(containerID)
    }
  }

  "createContainer" must {
    "work with a subdir" in {
      val containerID = ContainerID("test:hello-java", "https://github.com/GoogleCloudPlatform/buildpack-samples.git", "master", Some("sample-java-mvn"))
      dockerRm(containerID)
      createContainer(containerID)
    }
  }

  "createContainer" must {
    "work with a git tag" in {
      val containerID = ContainerID("test:hello-java", "https://github.com/jamesward/hello-java.git", "v0.0.1")
      dockerRm(containerID)
      createContainer(containerID)
    }
  }

  "containersStartAll" must {
    "start a containerID" in {
      val containerID = ContainerID("test:hello-java", "https://github.com/jamesward/hello-java.git", "master")
      val url = SbtDepContainersPlugin.containersStartAll(Seq(containerID), Seq.empty, logger)._1(containerID)
      val is = Source.fromInputStream(url.openConnection().getInputStream)
      is.mkString must equal ("hello, world")
    }
  }

  "containersStopAll" must {
    "stop a containerID" in {
      val containerID = ContainerID("test:hello-java", "https://github.com/jamesward/hello-java.git", "master")
      SbtDepContainersPlugin.containersStopAll(Seq(containerID), Seq.empty, logger)
    }
  }

}
