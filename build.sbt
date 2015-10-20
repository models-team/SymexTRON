import sbt._

resolvers += Resolver.sonatypeRepo("releases")

name := "VeriTRAN"

version := "1.0"

scalaVersion := "2.11.7"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.1.3"

libraryDependencies += "org.scalaz.stream" %% "scalaz-stream" % "0.8"

libraryDependencies += "com.googlecode.kiama" %% "kiama" % "1.8.0"

libraryDependencies += "org.scala-stm" %% "scala-stm" % "0.7"

val monocleLibraryVersion = "1.2.0-M1"

libraryDependencies ++= Seq(
  "com.github.julien-truffaut"  %%  "monocle-core"    % monocleLibraryVersion,
  "com.github.julien-truffaut"  %%  "monocle-generic" % monocleLibraryVersion,
  "com.github.julien-truffaut"  %%  "monocle-macro"   % monocleLibraryVersion,
  "com.github.julien-truffaut"  %%  "monocle-state"   % monocleLibraryVersion,
  "com.github.julien-truffaut"  %%  "monocle-law"     % monocleLibraryVersion % "test"
)

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"

fork in Test := true

triggeredMessage := Watched.clearWhenTriggered
