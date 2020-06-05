enablePlugins(GitVersioning)

sbtPlugin := true

name := "sbt-dep-containers"

organization := "com.jamesward"

scalaVersion := "2.12.11"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

description := "sbt plugin for container dependencies"

libraryDependencies ++= Seq(
  "org.eclipse.jgit" % "org.eclipse.jgit" % "5.7.0.202003110725-r",
  "com.github.docker-java" % "docker-java" % "3.2.1",

  "org.scalatest" %% "scalatest" % "3.0.8" % "test",
  "org.testcontainers" % "postgresql" % "1.13.0" % "test",
)

publishMavenStyle := false

licenses += "MIT" -> url("http://opensource.org/licenses/MIT")

git.useGitDescribe := true

bintrayVcsUrl := Some("git@github.com/jamesward/sbt-dep-containers.git")

bintrayRepository := "sbt-plugins"

bintrayOrganization in bintray := None

Test / testOptions += Tests.Argument("-oDF")
