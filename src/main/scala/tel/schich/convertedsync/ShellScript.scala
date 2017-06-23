package tel.schich.convertedsync

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.nio.file.{Files, Path}
import java.util

import scala.io.Source

case class ShellScript(executable: Path, inheritIO: Boolean) {

	private def build(args: Seq[String]): ProcessBuilder = {
		val command = executable.toString +: args
		val pb = new ProcessBuilder()
		pb.command(command:_*)
		if (inheritIO) {
			pb.inheritIO()
		}
		pb
	}

	def invoke(args: String*): Int = {
		build(args).start().waitFor()
	}

	def invokeWithOutput(args: String*): (Int, Iterator[String]) = {
		val pb = build(args)
		pb.redirectOutput(Redirect.PIPE)
		val proc = pb.start()
		(proc.waitFor(), Source.fromInputStream(proc.getInputStream).getLines())
	}
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
