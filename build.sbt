name := "ConvertedSync"

version := "1.0"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
    "org.apache.tika" % "tika-core" % "1.17",
    "com.github.scopt" %% "scopt" % "3.7.0"
)

mainClass in assembly := Some("tel.schich.convertedsync.Main")
    