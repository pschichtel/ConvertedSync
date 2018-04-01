package tel.schich.convertedsync.io

import java.nio.file.attribute.FileTime

import tel.schich.convertedsync.ConversionRule
import tel.schich.convertedsync.Util.OrderedFileTime

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

	def reframeCore(adapter: IOAdapter, base: String, ext: String): String = {
		adapter.join(base, localizedCore(adapter)) + ext
	}

	def localizedCore(adapter: IOAdapter): String = core.replace(FileInfo.CoreSeparator, adapter.separator)

	override def compare(that: FileInfo): Int = this.core.compareTo(that.core)
}

object FileInfo {
	var CoreSeparator = '/'
}

case class ConvertibleFile(sourceFile: FileInfo, rule: ConversionRule, existingTarget: Option[FileInfo]) {
	val isRenamed: Boolean = existingTarget.exists(_.core != sourceFile.core)
	val isInvalid: Boolean = existingTarget.forall(_.lastModified < sourceFile.lastModified)
	val mimeMismatched: Boolean = existingTarget.exists(!_.mime.equalsIgnoreCase(rule.targetMime))
}
