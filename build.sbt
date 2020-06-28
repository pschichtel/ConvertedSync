name := "ConvertedSync"

version := "1.0"

scalaVersion := "2.13.3"

libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0",
    "org.apache.tika" % "tika-core" % "1.24.1",
    "com.github.scopt" %% "scopt" % "4.0.0-RC2"
)

mainClass in assembly := Some("tel.schich.convertedsync.Main")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xsource:3")
