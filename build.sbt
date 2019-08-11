name := "ConvertedSync"

version := "1.0"

scalaVersion := "2.13.0"

libraryDependencies ++= Seq(
    "org.apache.tika" % "tika-core" % "1.20",
    "com.github.scopt" % "scopt_2.12" % "3.7.0"
)

mainClass in assembly := Some("tel.schich.convertedsync.Main")
    