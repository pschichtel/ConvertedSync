name := "ConvertedSync"

version := "1.0"

scalaVersion := "2.12.3"

libraryDependencies ++= Seq(
    "org.apache.tika" % "tika-core" % "1.14",
    "com.github.scopt" %% "scopt" % "3.5.0"
)

mainClass in assembly := Some("tel.schich.convertedsync.Main")
    