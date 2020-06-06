enablePlugins(SbtDepContainersPlugin)

name := "test-project-basic"

scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-simple" % "1.7.30" % "test",
  "org.postgresql" % "postgresql" % "42.2.12",
  "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.37.0",
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.37.0" % "test",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test",

  "org.slf4j" % "slf4j-simple" % "1.7.30",
)

containerDependencies := Seq(
  url("https://github.com/jamesward/hello-java.git") % "master",
  url("https://github.com/GoogleCloudPlatform/buildpack-samples.git") % "master" / "sample-java-mvn",
)
