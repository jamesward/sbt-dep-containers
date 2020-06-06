addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")

lazy val sbtDepContainersPlugin = RootProject(file("../..").getAbsoluteFile.toURI)

lazy val root = Project("test-project-withrevolver", file(".")).dependsOn(sbtDepContainersPlugin)
