package tel.schich.convertedsync.io

import java.io.File
import java.nio.file._

import tel.schich.convertedsync.Util
import tel.schich.convertedsync.mime.MimeDetector

import scala.collection.JavaConverters._

class LocalAdapter(mime: MimeDetector) extends IOAdapter
{
	override val separator: Char = File.separatorChar

	override def files(base: String): Seq[FileInfo] = {
		val basePath = Paths.get(base)
		Files.walk(basePath).iterator().asScala.filter(Files.isRegularFile(_)).map(pathToFileInfo(basePath, _)).toSeq
	}

	private def pathToFileInfo(base: Path, path: Path): FileInfo = {

		val relative = base.relativize(path)
		val fileName = relative.getFileName.toString
		val strippedRelative = fileName.lastIndexOf('.') match {
			case -1 => relative.toString
			case index =>
				val strippedName = fileName.substring(0, index)
				val parent = relative.getParent
				if (parent != null) parent.toString  + File.separatorChar + strippedName
				else strippedName

		}
		val lastModifiedTime = Files.getLastModifiedTime(path)
		val mimeType = mime.detectMime(path.toString, fileName)

		FileInfo(path.toString, fileName, strippedRelative, lastModifiedTime, mimeType)
	}

	override def delete(file: String): Boolean = {
		Files.delete(Paths.get(file))
		true
	}

	override def move(from: String, to: String): Boolean = {
		Util.moveFile(Paths.get(from), Paths.get(to))
	}

	override def copy(from: String, to: String): Boolean = {
		Files.copy(Paths.get(from), Paths.get(to))
		true
	}

	override def rename(from: String, to: String): Boolean = {
		move(from, to)
	}

	override def exists(path: String): Boolean = {
		Files.exists(Paths.get(path))
	}
}

object LocalAdapter {
	val name = "local"

	def isLocal(name: String): Boolean = this.name.equalsIgnoreCase(name)
}
