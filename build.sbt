name := "ConvertedSync"

version := "1.0"

scalaVersion := "3.4.0"
libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
    "org.apache.tika" % "tika-core" % "2.9.1",
    "com.github.scopt" %% "scopt" % "4.1.0"
)

mainClass := Some("tel.schich.convertedsync.Main")

scalacOptions ++= Seq("-unchecked", "-deprecation")
