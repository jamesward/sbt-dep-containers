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

  case class TestContainer(name: String) {
    lazy val className = "Dep" + name.capitalize
  }

  object autoImport {
    val Containers = config("Containers") extend Compile

    val containerBuilder = settingKey[String]("Buildpacks builder image")
    val containerDependencies = settingKey[Seq[ContainerID]]("Container dependencies")

    val containersTestContainers = taskKey[Set[TestContainer]]("Containers Test Containers")
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

  def containerIDsStart(containerIDs: Seq[ContainerID], logger: sbt.util.Logger): Map[ContainerID, URL] = {
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

    containerIDsWithURLs
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
    val containerIDsFiles = containerDependencies.value.map { containerID =>
      val file = sourceManaged.value / "main" / "depcontainers" / s"${containerID.className}.scala"

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

    // note: I tried to use reflection to start these but testcontainers didn't like something about the classpath (missing config)
    // todo: move these to a helper lib that can be added to the project
    val testContainersFiles = containersTestContainers.value.map { testContainer =>
      val file = sourceManaged.value / "main" / "depcontainers" / s"${testContainer.className}.scala"

      val guts = testContainer.name match {
        case "postgresql" =>
          """
            |    import scala.util.Using, java.io.{PrintWriter, File}
            |
            |    val container = new org.testcontainers.containers.PostgreSQLContainer()
            |    container.start()
            |
            |    val port = container.getMappedPort(org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT)
            |    val databaseUrl = s"postgres://${container.getUsername}:${container.getPassword}@${container.getContainerIpAddress}:$port/${container.getDatabaseName}"
            |    Using(new PrintWriter(file))(_.write(s"DATABASE_URL=$databaseUrl"))
            |
            |    Thread.currentThread().join()
            |
            |""".stripMargin

        case name =>
          s"""throw new Exception("Do not know how to handle $name")"""
      }

      val contents =
        s"""package org.testcontainers.depcontainers
           |
           |object ${testContainer.className} {
           |  def main(args: Array[String]): Unit = {
           |    val file = args.head
           |
           |    $guts
           |  }
           |}
           |
           |""".stripMargin

      IO.write(file, contents)

      file
    }

    containerIDsFiles ++ testContainersFiles
  }.dependsOn(Compile / managedClasspath)

  lazy val containersTestContainersStart = Def.taskDyn {
    val dir = target.value / "depcontainers"

    val startTasks = containersTestContainersTask.value.map { testContainer =>
      val file = dir / s"${testContainer.className}.env"

      if (file.exists()) file.delete()

      (Compile / bgRunMain).toTask(s" org.testcontainers.depcontainers.${testContainer.className} ${file.getAbsolutePath}").map(_ => ())
    }.toSeq

    val getEnvsTask = Def.task {
      containersTestContainersTask.value.map { testContainer =>
        val file = dir / s"${testContainer.className}.env"

        // ugly: wait for the file to exist
        while (!file.exists()) {
          Thread.sleep(1000)
        }

        val envs = IO.readLines(file).map { line =>
          val Array(key, value) = line.split("=")
          key -> value
        }.toMap

        testContainer.name -> envs
      }.toMap
    }

    Def.sequential(startTasks, getEnvsTask)
  }

  // todo: support better incremental usage
  lazy val containersStartTask = Def.task {
    val containerIDsURLs = containerIDsStart(containerDependencies.value, streams.value.log)

    val testContainersWithEnvVars = containersTestContainersStart.value

    val newEnvVars = containerIDsURLs.map { case (containerID, url) =>
      containerID.envVar -> url.toString
    } ++ testContainersWithEnvVars.foldLeft(Map.empty[String, String])(_ ++ _._2)

    newEnvVars
  }.dependsOn(containersCreate)

  lazy val containersTestContainersTask = Def.task {
    val s = streams.value

    // note: this must be assigned otherwise the sbt macro expansion pukes
    val testContainers = update.value.allModules.filter(_.organization == "org.testcontainers").flatMap { moduleID =>
      moduleID.name match {
        case "postgresql" =>
          Vector(TestContainer("postgresql"))
        case "testcontainers" | "jdbc" | "database-commons" =>
          Vector.empty
        case name =>
          s.log.warn(s"Could not figure out how to start testcontainer $name")
          Vector.empty
      }
    }.toSet

    testContainers
  }

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies += "com.dimafeng" %% "testcontainers-scala-core" % "0.37.0",

    Compile / sourceGenerators += generateDependencyContainersTask.taskValue,

    containersTestContainers := containersTestContainersTask.value,

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

    Containers / discoveredMainClasses := {
      val allMains = (Compile / discoveredMainClasses).value
      allMains.filterNot(_.startsWith("org.testcontainers.depcontainers"))
    },

    Containers / run / selectMainClass := (Compile / mainClass).value orElse Defaults.askForMainClass((Containers / discoveredMainClasses).value),

    Containers / run / mainClass := (Containers / run / selectMainClass).value,

    // todo: stop containers?
    Containers / run := {
      Defaults.runTask(
        Runtime / fullClasspath,
        Containers / run / mainClass,
        Containers / run / runner,
      ).evaluated
    },

    Test / test := (Test / test).dependsOn(containersCreate).value,

    Compile / run := (Compile / run).evaluated,
  )


}
