import java.io.File
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}

import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata

import scala.collection.JavaConversions._

case class FileDescription(fullPath: Path, withoutExtension: String, lastModified: FileTime, mime: String)

object DirectoryScanner {

	val tika = new TikaConfig()

	def scan(path: Path): Stream[Path] = {
		if (Files.isDirectory(path)) {
			Files.walk(path).iterator().toStream.filter(Files.isRegularFile(_))
		} else {
			Stream.empty
		}
	}

	def scanEnriched(path: Path, detectMime: Boolean = true): List[FileDescription] = {
		val files = scan(path)
		val metadata = new Metadata
		files.map { f =>
			val relative = path.relativize(f)
			val fileName = relative.getFileName.toString
			val strippedRelative = fileName.lastIndexOf('.') match {
				case -1 => relative.toString
				case index =>
					val strippedName = fileName.substring(0, index)
					val parent = relative.getParent
					if (parent != null) parent.toString  + File.separatorChar + strippedName
					else strippedName

			}

			val mimeType = if (detectMime) {
				val tikaStream = TikaInputStream.get(f)
				val mediaType = try {
					tika.getDetector.detect(tikaStream, metadata)
				} finally {
					tikaStream.close()
				}
				mediaType.toString
			} else "unknown"

			FileDescription(f, strippedRelative, Files.getLastModifiedTime(f), mimeType)
		}.toList
	}
}
