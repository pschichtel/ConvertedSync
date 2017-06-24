package tel.schich.convertedsync

import java.io.{File, InputStream}
import java.lang.ProcessBuilder.Redirect
import java.nio.file.{Files, Path}

import scala.io.Source

case class ShellScript(executable: Path, inheritIO: Boolean) {

	private def build(args: Seq[String], inheritIO: Boolean): ProcessBuilder = {
		val command = executable.toString +: args
		val pb = new ProcessBuilder()
		pb.command(command:_*)
		if (inheritIO) {
			pb.inheritIO()
		}
		pb
	}

	def invoke(args: Seq[String], inheritIO: Boolean = this.inheritIO): Int = {
		build(args, inheritIO).start().waitFor()
	}

	def invokeAndRead(args: Seq[String], inheritIO: Boolean = this.inheritIO): (Int, String) = {
		val pb = build(args, inheritIO)
		pb.redirectOutput(Redirect.PIPE)
		val proc = pb.start()
		val stdOut = readOutput(proc.getInputStream)
		val result = proc.waitFor()
		(result, stdOut)
	}

	def invokeAndReadError(args: Seq[String], inheritIO: Boolean = this.inheritIO): (Int, String, String) = {
		val pb = build(args, inheritIO)
		pb.redirectOutput(Redirect.PIPE)
		pb.redirectError(Redirect.PIPE)
		val proc = pb.start()
		val stdOut = readOutput(proc.getInputStream)
		val stdErr = readOutput(proc.getErrorStream)
		val result = proc.waitFor()
		(result, stdOut, stdErr)
	}

	private def readOutput(in: InputStream) = Source.fromInputStream(in).mkString
}

object ShellScript {
	def resolve(path: Path, inheritIO: Boolean = true): Option[ShellScript] = {
		val pathExt = sys.env.getOrElse("PATHEXT", "")
		val possiblePaths = if (pathExt.length > 0) {
			val parent = path.getParent
			val name = path.getFileName
			pathExt.split(File.pathSeparatorChar).map(ext => parent.resolve(name + ext)).toSeq :+ path
		} else Seq(path)

		val executablePaths = possiblePaths.filter(Files.isExecutable)
		if (executablePaths.nonEmpty) Some(ShellScript(executablePaths.head, inheritIO))
		else None
	}
}
