package tel.schich.convertedsync.io

import java.nio.file.attribute.FileTime

/** File metadata used during the conversion process.
  *
  * @param base         the base directory
  * @param fullPath     the full absolute path to the file
  * @param fileName     the filename part of the path
  * @param core         the fullPath stripped from its base prefix and file extension
  * @param previousCore the core previously seen on this file
  * @param extension    the extension that was stripped after the core
  * @param lastModified the instant the file was last modified
  * @param mime         the mime type
  */
case class FileInfo(base: String, fullPath: String,
                    fileName: String, core: String,
                    previousCore: Option[String], extension: String,
                    lastModified: FileTime, mime: String) extends Ordered[FileInfo] {

	def reframeCore(base: String, ext: String): String = {
		base + core + ext
	}

	override def compare(that: FileInfo): Int = this.core.compareTo(that.core)
}
