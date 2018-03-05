package tel.schich.convertedsync.io

import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit.SECONDS

import tel.schich.convertedsync.mime.MimeDetector
import tel.schich.convertedsync.{ShellScript, Util}

class ShellAdapter(mime: MimeDetector, script: ShellScript, localSeparator: Char) extends IOAdapter {

	val DefaultSeparator: Char = '/'

	override lazy val separator: Char = {
		val (result, out, _) = script.invokeAndReadError(Seq("separator"))
		if (result == 0 && out.nonEmpty) out.headOption.getOrElse(DefaultSeparator)
		else DefaultSeparator
	}

	private def lineToFileInfo(base: String, line: String): Option[FileInfo] = {
		line.indexOf(',') match {
			case -1 =>
				println("Line is not valid:")
				println(s"   $line")
				None
			case n =>
				if (n == line.length - 1 || n == 0) None
				else {
					val path = line.substring(n + 1)
					val fileName = Util.fileName(path, separator)
					val lastModifiedDate = parseLastModDate(line.substring(0, n))
					val mimeType = mime.detectMime(path, fileName)
					val core = extractCore(base, path)
					// TODO optionally support the previous core
					Some(FileInfo(path, fileName, core, None, lastModifiedDate, mimeType))
				}
		}
	}

	override def files(base: String): Seq[FileInfo] = {
		script.invokeAndRead(Seq("list", base)) match {
			case (0, out) =>
				Util.splitLines(out).filter(_.nonEmpty).flatMap(lineToFileInfo(base, _))
			case _ =>
				Seq.empty
		}
	}

	private def parseLastModDate(dateStr: String): FileTime = {
		FileTime.from(dateStr.toLong, SECONDS)
	}

	private def extractCore(base: String, path: String): String = {
		val relativePath = path.substring(base.length).replaceAll("^" + separator, "")
		val (prefix, fileName) = {
			relativePath.lastIndexOf(separator) match {
				case -1 => ("", relativePath)
				case n  => (relativePath.substring(0, n + 1), relativePath.substring(n + 1))
			}
		}
		val withoutExtension = fileName.lastIndexOf('.') match {
			case -1 => prefix + fileName
			case n  => prefix + fileName.substring(0, n)
		}
		withoutExtension.replace('/', localSeparator)
	}

	override def delete(file: String): Boolean = {
		script.invoke(Seq("rm", file)) == 0
	}

	override def move(from: String, to: String): Boolean = {
		script.invoke(Seq("move", from, to)) == 0
	}

	override def copy(from: String, to: String): Boolean = {
		script.invoke(Seq("copy", from, to)) == 0
	}

	override def rename(from: String, to: String): Boolean = {
		script.invoke(Seq("rename", from, to)) == 0
	}

	override def exists(path: String): Boolean = {
		script.invoke(Seq("exists", path)) == 0
	}

	override def mkdirs(path: String): Boolean = {
		script.invoke(Seq("mkdirs", path)) == 0
	}

	override def relativeFreeSpace(path: String): Double = {
		val (result, stdOut) = script.invokeAndRead(Seq("freespace", path))
		if (result == 0 && stdOut.contains(',')) {
			val Array(free, total) = stdOut.trim.split(",", 2).map(_.toDouble)
			free / total
		} else {
			throw new Exception(s"Unable to parse free space: $stdOut")
		}
	}

	override def purgeEmptyFolders(path: String): Boolean = {
		script.invoke(Seq("purge-empty", path)) == 0
	}
}
