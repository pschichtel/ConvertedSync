package tel.schich.convertedsync

import java.nio.file._

/**
  * Created by phillip on 21.06.17.
  */
object Util {
	def moveFile(from: Path, to: Path): Boolean = {
		try {
			// Try an atomic move (rename in Linux) operation
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
			true
		} catch {
			case _: AtomicMoveNotSupportedException =>
				try {
					// Try a simple move operation (move in Linux)
					Files.move(from, to)
					true
				} catch {
					case _: FileSystemException =>
						// Copy the source and delete it afterwards if successful
						Files.copy(from, to)
						Files.delete(from)
						true
				}
		}
		false
	}

	def fileName(path: String, separator: Char): String = {
		path.lastIndexOf(separator) match {
			case -1 => path
			case n => path.substring(n + 1)
		}
	}

	def stripExtension(path: String, separator: Char): String = {
		val lastSeparator = path.lastIndexOf(separator)
		path.lastIndexOf('.') match {
			case -1 => path
			case n if n < lastSeparator => path
			case n => path.substring(0, n)
		}
	}

	def parentPath(path: String, separator: Char): String = {
		path.lastIndexOf(separator) match {
			case n if n <= 0 => "" + separator
			case n => path.substring(0, n)
		}
	}

	def splitLines(in: String): Seq[String] = in.split("(?:\r\n|\r|\n)", -1)
}
