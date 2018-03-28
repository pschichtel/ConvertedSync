package tel.schich.convertedsync.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._
import java.nio.file.attribute.UserDefinedFileAttributeView

import tel.schich.convertedsync.Util
import tel.schich.convertedsync.mime.MimeDetector

import scala.collection.JavaConverters._

class LocalAdapter(mime: MimeDetector) extends IOAdapter
{
	val PreviousCoreAttributeName = "previous-core"

	override val separator: Char = File.separatorChar

	override def files(base: String): IndexedSeq[FileInfo] = {
		val basePath = Paths.get(base)
		Files.walk(basePath).iterator().asScala.filter(Files.isRegularFile(_)).map(pathToFileInfo(basePath, _)).toVector
	}

	private def pathToFileInfo(base: Path, path: Path): FileInfo = {

		val relative = base.relativize(path)
		val fileName = relative.getFileName.toString
		val (strippedRelative, extension) = fileName.lastIndexOf('.') match {
			case -1 => (relative.toString, "")
			case index =>
				val strippedName = fileName.substring(0, index)
				val ext = fileName.substring(index)
				val parent = relative.getParent
				if (parent != null) (parent.toString  + File.separatorChar + strippedName, ext)
				else (strippedName, ext)

		}
		val lastModifiedTime = Files.getLastModifiedTime(path)
		val mimeType = mime.detectMime(path.toString, fileName)

		Files.getFileStore(path).supportsFileAttributeView(classOf[UserDefinedFileAttributeView])

		FileInfo(base.toString, path.toString,
			fileName, strippedRelative,
			previousCore(path), extension,
			lastModifiedTime, mimeType)
	}

	private def attributeView(path: Path): Option[UserDefinedFileAttributeView] = {
		val store = Files.getFileStore(path)
		val viewType = classOf[UserDefinedFileAttributeView]
		if (store.supportsFileAttributeView(viewType)) {
			Some(Files.getFileAttributeView(path, viewType))
		} else None
	}

	private def setAttribute(path: Path, name: String, value: String): Boolean = {
		attributeView(path) match {
			case Some(view) =>
				val buf = UTF_8.encode(value)
				// loop until the buffer is empty
				while (buf.hasRemaining) view.write(name, buf)
				true
			case None => false
		}
	}

	private def readAttribute(path: Path, name: String): Option[String] = {
		attributeView(path).flatMap { view =>
			if (view.list().contains(PreviousCoreAttributeName)) {
				val buf = ByteBuffer.allocateDirect(view.size(PreviousCoreAttributeName))
				// loop until the buffer is full
				while (buf.hasRemaining) view.read(PreviousCoreAttributeName, buf)
				Some(UTF_8.decode(buf).toString)
			} else None
		}
	}

	private def previousCore(path: Path): Option[String] =
		readAttribute(path, PreviousCoreAttributeName)

	def updatePreviousCore(path: String, previousCore: String): Boolean =
		setAttribute(Paths.get(path), PreviousCoreAttributeName, previousCore)

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

	override def mkdirs(path: String): Boolean = {
		Files.createDirectories(Paths.get(path))
		true
	}

	override def relativeFreeSpace(path: String): Double = {
		val fileStore = Files.getFileStore(Paths.get(path))
		fileStore.getUsableSpace / fileStore.getTotalSpace.asInstanceOf[Double]
	}

	override def purgeEmptyFolders(path: String): Boolean = {
		Files.walk(Paths.get(path))
			.filter(p => Files.isDirectory(p) && !Files.list(p).findAny().isPresent)
			.forEach(Files.delete(_))
		true
	}
}
