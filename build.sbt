name := "ConvertedSync"

version := "1.0"

scalaVersion := "3.7.4"
libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0",
    "org.apache.tika" % "tika-core" % "3.2.3",
    "com.github.scopt" %% "scopt" % "4.1.0"
)

mainClass := Some("tel.schich.convertedsync.Main")

scalacOptions ++= Seq("-unchecked", "-deprecation")
