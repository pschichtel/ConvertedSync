package tel.schich.convertedsync.mime

trait MimeDetector {
	def detectMime(path: String, fileName: String): String
}
