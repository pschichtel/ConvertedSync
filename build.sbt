name := "ConvertedSync"

version := "1.0"

scalaVersion := "3.6.2"
libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
    "org.apache.tika" % "tika-core" % "3.0.0",
    "com.github.scopt" %% "scopt" % "4.1.0"
)

mainClass := Some("tel.schich.convertedsync.Main")

scalacOptions ++= Seq("-unchecked", "-deprecation")
