package tel.schich.convertedsync.io

import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit.SECONDS

import tel.schich.convertedsync.mime.MimeDetector
import tel.schich.convertedsync.{ShellScript, Util}

import scala.collection.parallel.ParSeq

class ShellAdapter(mime: MimeDetector, script: ShellScript) extends IOAdapter {

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
					val (core, extension) = extractCore(base, path)
					// TODO optionally support the previous core: currently blocked by the lack of a safe path encoding
					// Currently we can only handle a single path (mostly) safe, the previous core would
					// introduce a second one.
					Some(FileInfo(base, path, fileName, core, None, extension, lastModifiedDate, mimeType))
				}
		}
	}

	override def files(base: String): ParSeq[FileInfo] = {
		script.invokeAndRead(Seq("list", base)) match {
			case (0, out) =>
				Util.splitLines(out).par.filter(_.nonEmpty).flatMap(lineToFileInfo(base, _))
			case _ =>
				Vector.empty.par
		}
	}

	private def parseLastModDate(dateStr: String): FileTime = {
		FileTime.from(dateStr.toLong, SECONDS)
	}

	private def extractCore(base: String, path: String): (String, String) = {
		val relative = path
				.substring(base.length)
				.replace(separator, FileInfo.CoreSeparator)
		    	.dropWhile(_ == FileInfo.CoreSeparator)

		val sepPos = relative.lastIndexOf(FileInfo.CoreSeparator)
		relative.lastIndexOf('.') match {
			case n if n > sepPos => relative.splitAt(n)
			case _ => (relative, "")
		}
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

	override def updatePreviousCore(path: String, previousCore: String): Boolean = {
		script.invoke(Seq("update_previous_core", path, previousCore)) == 0
	}

	override def relativeFreeSpace(path: String): Either[String, Double] = {
		val (result, stdOut) = script.invokeAndRead(Seq("freespace", path))
		if (result == 0) {
			if (stdOut.contains(',')) {
				val Array(free, total) = stdOut.trim.split(",", 2).map(_.toDouble)
				Right(free / total)
			} else Left(s"Unable to parse free space: $stdOut")
		} else Left("free space command failed!")
	}

	override def purgeEmptyFolders(path: String): Boolean = {
		script.invoke(Seq("purge-empty", path)) == 0
	}
}
