lazy val sbtDepContainersPlugin = RootProject(file("../..").getAbsoluteFile.toURI)

lazy val root = Project("test-project", file(".")).dependsOn(sbtDepContainersPlugin)
