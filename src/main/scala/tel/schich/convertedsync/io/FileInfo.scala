package tel.schich.convertedsync.io

import java.nio.file.attribute.FileTime

/** File metadata used during the conversion process.
  *
  * @param fullPath     the full absolute path to the file
  * @param fileName     the filename part of the path
  * @param core         the fullPath stripped from its base prefix and file extension
  * @param lastModified the instant the file was last modified
  * @param mime         the mime type
  */
case class FileInfo(fullPath: String, fileName: String,
                    core: String, lastModified: FileTime,
                    mime: String)
