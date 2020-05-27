enablePlugins(SbtDepContainersPlugin)

name := "test-project"

scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-simple" % "1.7.30" % "test",
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.37.0" % "test",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test",
)

containerDependencies := Seq(
  "HelloJava" ~ "https://github.com/jamesward/hello-java.git" ~ "master",
  "SampleJava" ~ "https://github.com/GoogleCloudPlatform/buildpack-samples.git" ~ "master" / "sample-java-mvn",
)

javacOptions ++= Seq("-source", "11", "-target", "11")

scalacOptions += "-target:jvm-11"

initialize := {
  val _ = initialize.value
  val javaVersion = sys.props("java.specification.version")
  if (javaVersion != "11")
    sys.error("Java 11 is required for this project. Found " + javaVersion + " instead")
}
