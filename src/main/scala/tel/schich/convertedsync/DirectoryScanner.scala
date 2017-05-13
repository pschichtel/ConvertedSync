package tel.schich.convertedsync

import java.io.{Closeable, File}
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}

import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaMetadataKeys}
import org.apache.tika.mime.MimeTypes

import scala.collection.JavaConverters._

case class FileDescription(fullPath: Path, withoutExtension: String, lastModified: FileTime, mime: String)

class DirectoryScanner(tikaConf: TikaConfig = TikaConfig.getDefaultConfig) {

	def scan(path: Path): Iterator[Path] = {
		if (Files.isDirectory(path)) {
			Files.walk(path).iterator().asScala.filter(Files.isRegularFile(_))
		} else {
			Iterator.empty
		}
	}

	def scanEnriched(path: Path, detectMime: Boolean = true, allowFileAccess: Boolean = true, warnWrongExtension: Boolean = false): List[FileDescription] = {
		val files = scan(path)
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

			val meta = new Metadata
			meta.set(TikaMetadataKeys.RESOURCE_NAME_KEY, f.getFileName.toString)
			val detector = tikaConf.getDetector
			val mimeType = if (detectMime) {
				val stream =
					if (allowFileAccess) TikaInputStream.get(f)
					else null
				val mediaType = withResource(stream) { s =>
					detector.detect(s, meta)
				}
				mediaType.toString
			} else MimeTypes.OCTET_STREAM

			if (warnWrongExtension && !mimeType.equals(detector.detect(null, meta).toString)) {
				println(s"Warning for $f: File extension does not match file type!")
			}

			FileDescription(f, strippedRelative, Files.getLastModifiedTime(f), mimeType)
		}.toList
	}

	private def withResource[T <: Closeable, U](res: T)(f: T => U): U = {
		try {
			f(res)
		} finally {
			if (res != null) {
				res.close()
			}
		}
	}
}
