package tel.schich.convertedsync.mime

import org.apache.tika.mime.MimeTypes

class StaticMimeDetector(mime: String = MimeTypes.OCTET_STREAM) extends MimeDetector {
	override def detectMime(path: String, fileName: String): String = mime
}
