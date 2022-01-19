package tel.schich.convertedsync.mime

import java.io.Closeable
import java.nio.file.Paths
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}

class TikaMimeDetector(tikaConf: TikaConfig, examineFileContent: Boolean, warnWrongExtension: Boolean)
	extends MimeDetector {

	override def detectMime(path: String, fileName: String): String = {
		val meta = new Metadata
		meta.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
		val detector = tikaConf.getDetector
		val stream =
			if (examineFileContent) TikaInputStream.get(Paths.get(path))
			else null
		val mimeType = withResource(stream) { s =>
			detector.detect(s, meta)
		}.toString

		if (warnWrongExtension && !mimeType.equals(detector.detect(null, meta).toString)) {
			println(s"Warning for $path: File extension does not match file type!")
		}

		mimeType
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
