package com.jamesward.sbtdepcontainers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.model.{Bind, Frame, HostConfig, StreamType, Volume, WaitResponse}
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder}
import org.eclipse.jgit.api.Git
import sbt.{AutoPlugin, Def, _}
import sbt.Keys._
import sbt.plugins.JvmPlugin

import scala.language.implicitConversions
import scala.collection.JavaConverters._

// todo: should testcontainers-scala-core be a dep of the plugin?

object SbtDepContainersPlugin extends AutoPlugin {

  val defaultContainerBuilder = "gcr.io/buildpacks/builder"

  override def requires = JvmPlugin

  case class ContainerIDBuilderID(name: String) {
    def ~(gitUrl: String): ContainerIDBuilderGitUrl = ContainerIDBuilderGitUrl(name, gitUrl)
  }

  case class ContainerIDBuilderGitUrl(name: String, gitUrl: String) {
    def ~(branchOrTag: String): ContainerID = ContainerID(name, gitUrl, branchOrTag)
  }

  case class ContainerID(name: String, gitUrl: String, branchOrTag: String, maybeSubdir: Option[String] = None) {
    def /(subdir: String): ContainerID = copy(maybeSubdir = Some(subdir))

    lazy val validName = name.toLowerCase().replaceAll("[^a-z]", "-").replaceAll("--", "-")
    lazy val dockerTag = s"$validName:$branchOrTag"
  }

  object autoImport {
    val containerBuilder = settingKey[String]("Buildpacks builder image")
    val containerDependencies = settingKey[Seq[ContainerID]]("Container dependencies")

    val containersCreate = taskKey[Unit]("Create the dependency containers")

    // todo: validate name
    implicit def stringToContainerIDBuilder(name: String): ContainerIDBuilderID = ContainerIDBuilderID(name)
  }

  import autoImport._

  def createContainer(target: File, containerBuilder: String, logger: Logger)(containerID: ContainerID): Unit = {
    val gitUri = new URI(containerID.gitUrl)
    val gitDir = target / "depcontainers" / gitUri.getHost / gitUri.getPath / containerID.branchOrTag

    val ref = Git.lsRemoteRepository().setRemote(containerID.gitUrl).call().asScala.find(_.getName.endsWith("/" + containerID.branchOrTag)).get

    if (gitDir.exists()) {
      logger.info(s"Updating $gitUri ${ref.getName}")

      Git.open(gitDir).pull().call()
    }
    else {
      logger.info(s"Cloning $gitUri ${ref.getName}")

      Git.cloneRepository()
        .setURI(gitUri.toString)
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

    val container = dockerClient
      .createContainerCmd("gcr.io/k8s-skaffold/pack")
      .withHostConfig(hostConfig)
      .withWorkingDir("/workspace")
      .withCmd(command.asJava)
      .exec()

    class Logger extends ResultCallback.Adapter[Frame] {
      override def onNext(frame: Frame): Unit = {
        frame.getStreamType match {
          case StreamType.STDOUT | StreamType.STDERR => logger.info(new String(frame.getPayload).stripLineEnd)
          case _ => logger.error(frame.toString)
        }
      }
    }

    dockerClient.startContainerCmd(container.getId).exec()
    dockerClient.logContainerCmd(container.getId).withStdErr(true).withStdOut(true).withFollowStream(true).withTailAll().exec(new Logger()).awaitCompletion()

    val exit = dockerClient.waitContainerCmd(container.getId).exec(new WaitContainerResultCallback()).awaitStatusCode()
    if (exit != 0)
      throw new Exception("Process did not succeed")
  }

  override lazy val globalSettings = Seq(
    containerBuilder := defaultContainerBuilder,
    containerDependencies := Seq.empty[ContainerID],
  )

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies += "com.dimafeng" %% "testcontainers-scala-core" % "0.37.0",

    Compile / sourceGenerators += Def.task {
      containerDependencies.value.map { containerID =>
        val file = sourceManaged.value / "main" / s"${containerID.name}.scala"

        val contents =
          s"""import java.net.URL
             |
             |import com.dimafeng.testcontainers.GenericContainer
             |import org.testcontainers.containers.wait.strategy.Wait
             |
             |
             |class ${containerID.name}() extends GenericContainer("${containerID.dockerTag}", Seq(8080), Map("PORT" -> "8080"), waitStrategy = Some(Wait.forListeningPort())) {
             |  def rootUrl: URL = new URL(s"http://$$containerIpAddress:$${mappedPort(8080)}/")
             |}
             |
             |object ${containerID.name} {
             |  case class Def() extends GenericContainer.Def(new ${containerID.name})
             |}
             |
             |""".stripMargin

        IO.write(file, contents)

        file
      }
    }.dependsOn(Compile / managedClasspath).taskValue,

    containersCreate := containerDependencies.value.foreach(createContainer(target.value, containerBuilder.value, streams.value.log)),

    Test / test := (Test / test).dependsOn(containersCreate).value,

    Compile / run := (Compile / run).dependsOn(containersCreate).evaluated,
  )


}
