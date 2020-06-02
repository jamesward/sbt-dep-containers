package com.jamesward.sbtdepcontainers

import java.net.URL

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.model.Ports.Binding
import com.github.dockerjava.api.model.{Bind, ExposedPort, Frame, HostConfig, PortBinding, StreamType, Volume}
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder}
import org.eclipse.jgit.api.Git
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.{AutoPlugin, Def, _}

import scala.collection.JavaConverters._
import scala.language.implicitConversions
import scala.util.Random

// todo: should testcontainers-scala-core be a dep of the plugin?

object SbtDepContainersPlugin extends AutoPlugin {

  val defaultContainerBuilder = "gcr.io/buildpacks/builder"

  override def requires = JvmPlugin

  override def trigger = allRequirements

  case class ContainerIDBuilder(gitUrl: URL) {
    def %(branchOrTag: String): ContainerID = ContainerID(gitUrl, branchOrTag)
  }

  case class ContainerID(gitUrl: URL, branchOrTag: String, maybeSubdir: Option[String] = None) {
    def /(subdir: String): ContainerID = copy(maybeSubdir = Some(subdir))

    lazy val pathParts = gitUrl.getPath.stripSuffix(".git").split("/") ++ maybeSubdir.map(_.split("/")).getOrElse(Array.empty)
    lazy val name = pathParts.last.toLowerCase().replaceAll("[^a-z]", "-").replaceAll("--", "-")

    lazy val packageName = {
      val reversedDomain = gitUrl.getHost.split("\\.").reverse.map(_.replaceAll("[^a-z]", "")).mkString(".").toLowerCase

      if (pathParts.length > 1) {
        reversedDomain + pathParts.dropRight(1).map(_.toLowerCase).map(_.replaceAll("[^a-z]", "")).mkString(".")
      }
      else {
        reversedDomain
      }
    }


    lazy val className = "Dep" + name.split("-").map(_.capitalize).mkString

    lazy val envVar = name.toUpperCase.replaceAll("-", "_") + "_URL"
    lazy val dockerTag = s"$name:$branchOrTag"
  }

  object autoImport {
    val Containers = config("Containers") extend Compile

    val containerBuilder = settingKey[String]("Buildpacks builder image")
    val containerDependencies = settingKey[Seq[ContainerID]]("Container dependencies")

    val containersCreate = taskKey[Unit]("Create the dependency containers")
    val containersStart = taskKey[Map[String, String]]("Start the dependency containers")
    val containersStop = taskKey[Unit]("Stop the dependency containers")

    implicit def urlToContainerIDBuilder(gitUrl: URL): ContainerIDBuilder = ContainerIDBuilder(gitUrl)
  }

  import autoImport._

  class Logger(underlying: sbt.util.Logger, maybePrefix: Option[String] = None) extends ResultCallback.Adapter[Frame] {
    val prefix = maybePrefix.map(prefix => s"[$prefix] ").getOrElse("")
    override def onNext(frame: Frame): Unit = {
      frame.getStreamType match {
        case StreamType.STDOUT | StreamType.STDERR => underlying.info(prefix + new String(frame.getPayload).stripLineEnd)
        case _ => underlying.error(prefix + frame.toString)
      }
    }
  }

  def createContainer(target: File, containerBuilder: String, logger: sbt.util.Logger)(containerID: ContainerID): Unit = {
    val gitDir = target / "depcontainers" / containerID.gitUrl.getHost / containerID.gitUrl.getPath / containerID.branchOrTag

    val ref = Git.lsRemoteRepository().setRemote(containerID.gitUrl.toString).call().asScala.find(_.getName.endsWith("/" + containerID.branchOrTag)).get

    if (gitDir.exists()) {
      logger.info(s"Updating ${containerID.gitUrl} ${ref.getName}")

      Git.open(gitDir).pull().call()
    }
    else {
      logger.info(s"Cloning ${containerID.gitUrl} ${ref.getName}")

      Git.cloneRepository()
        .setURI(containerID.gitUrl.toString)
        .setBranchesToClone(Seq(ref.getName).asJava)
        .setBranch(ref.getName)
        .setDirectory(gitDir)
        .call()
    }

    val baseCommand = Seq("pack", "build", containerID.dockerTag, s"--builder=$containerBuilder")
    val command = containerID.maybeSubdir.fold(baseCommand) { subdir =>
      baseCommand :+ s"--path=$subdir"
    }

    val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build()
    val dockerClient = DockerClientBuilder.getInstance(config).build()
    val hostConfig = HostConfig
      .newHostConfig()
      .withBinds(
        new Bind(gitDir.getAbsolutePath, new Volume("/workspace")),
        new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock"))
      )

    // todo: use builder image directly
    val container = dockerClient
      .createContainerCmd("gcr.io/k8s-skaffold/pack")
      .withHostConfig(hostConfig)
      .withWorkingDir("/workspace")
      .withCmd(command.asJava)
      .exec()

    dockerClient.startContainerCmd(container.getId).exec()
    dockerClient.logContainerCmd(container.getId).withStdErr(true).withStdOut(true).withFollowStream(true).withTailAll().exec(new Logger(logger)).awaitCompletion()

    val exit = dockerClient.waitContainerCmd(container.getId).exec(new WaitContainerResultCallback()).awaitStatusCode()
    if (exit != 0)
      throw new Exception("Process did not succeed")
  }

  def containersStartAll(containerIDs: Seq[ContainerID], moduleIDs: Seq[ModuleID], logger: sbt.util.Logger): (Map[ContainerID, URL], Map[ModuleID, _]) = {
    // todo: do not start if already running

    val containerIDsWithURLs = containerIDs.map { containerID =>
      val port = Random.nextInt(Char.MaxValue.toInt - 1024) + 1024
      val bindPort = Binding.bindPort(port)
      val exposedPort = ExposedPort.tcp(8080)

      val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build()
      val dockerClient = DockerClientBuilder.getInstance(config).build()
      val hostConfig = HostConfig
        .newHostConfig()
        .withPortBindings(new PortBinding(bindPort, exposedPort))

      // todo: use builder image directly
      val container = dockerClient
        .createContainerCmd(containerID.dockerTag)
        .withExposedPorts(exposedPort)
        .withHostConfig(hostConfig)
        .withEnv("PORT=8080")
        .exec()

      logger.info(s"Starting container for ${containerID.dockerTag}")

      dockerClient.startContainerCmd(container.getId).exec()

      dockerClient.logContainerCmd(container.getId).withStdErr(true).withStdOut(true).withFollowStream(true).withTailAll().exec(new Logger(logger, Some(containerID.dockerTag))).awaitStarted()

      containerID -> new URL(s"http://localhost:$port")
    }.toMap

    (containerIDsWithURLs, Map.empty)
  }

  def containersStopAll(containerIDs: Seq[ContainerID], moduleIDs: Seq[ModuleID], logger: sbt.util.Logger): Unit = {
    containerIDs.foreach { containerID =>
      val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build()
      val dockerClient = DockerClientBuilder.getInstance(config).build()
      val maybeContainer = dockerClient.listContainersCmd().exec().asScala.find(_.getImage == containerID.dockerTag)
      maybeContainer.foreach { container =>
        if (container.getState == "running") {
          logger.info(s"Stopping container for ${containerID.dockerTag}")
          dockerClient.stopContainerCmd(container.getId).exec()
        }
      }
    }
  }

  override lazy val globalSettings = Seq(
    containerBuilder := defaultContainerBuilder,
    containerDependencies := Seq.empty[ContainerID],
  )

  lazy val generateDependencyContainersTask = Def.task {
    containerDependencies.value.map { containerID =>
      val file = sourceManaged.value / "main" / "depcontainers" / s"${containerID.name}.scala"

      val contents =
        s"""package ${containerID.packageName}
           |
           |import java.net.URL
           |
           |import com.dimafeng.testcontainers.GenericContainer
           |import org.testcontainers.containers.wait.strategy.Wait
           |
           |
           |class ${containerID.className}() extends GenericContainer("${containerID.dockerTag}", Seq(8080), Map("PORT" -> "8080"), waitStrategy = Some(Wait.forListeningPort())) {
           |  def rootUrl: URL = new URL(s"http://$$containerIpAddress:$${mappedPort(8080)}/")
           |}
           |
           |object ${containerID.className} {
           |  case class Def() extends GenericContainer.Def(new ${containerID.className})
           |}
           |
           |""".stripMargin

      IO.write(file, contents)

      file
    }
  }.dependsOn(Compile / managedClasspath)

  // todo: support better incremental usage
  // todo: moduleIDs
  lazy val containersStartTask = Def.task {
    val (containerIDsURLs, moduleIDsData) = containersStartAll(containerDependencies.value, libraryDependencies.value, streams.value.log)

    val newEnvVars = containerIDsURLs.map { case (containerID, url) =>
      containerID.envVar -> url.toString
    }

    newEnvVars
  }.dependsOn(containersCreate)


  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies += "com.dimafeng" %% "testcontainers-scala-core" % "0.37.0",

    Compile / sourceGenerators += generateDependencyContainersTask.taskValue,

    containersCreate := containerDependencies.value.foreach(createContainer(target.value, containerBuilder.value, streams.value.log)),

    containersStart := containersStartTask.value,

    containersStop := containersStopAll(containerDependencies.value, libraryDependencies.value, streams.value.log),

    Containers / run / runner := {
      val envVars = containersStart.value
      val opts = forkOptions.value.withEnvVars(envVars)
      val options = javaOptions.value
      streams.value.log.debug(s"javaOptions: $options")
      new ForkRun(opts)
    },

    // todo: stop containers?
    Containers / run := {
      Defaults.runTask(
        Runtime / fullClasspath,
        Compile / run / mainClass,
        Containers / run / runner,
      ).evaluated
    },

    Test / test := (Test / test).dependsOn(containersCreate).value,

    Compile / run := (Compile / run).evaluated,
  )


}
