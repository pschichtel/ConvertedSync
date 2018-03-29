package tel.schich.convertedsync.io

import scala.collection.parallel.ParSeq

trait IOAdapter {
	def separator: Char
	def files(base: String): ParSeq[FileInfo]
	def updatePreviousCore(path: String, previousCore: String): Boolean
	def delete(file: String): Boolean
	def copy(from: String, to: String): Boolean
	def move(from: String, to: String): Boolean
	def rename(from: String, to: String): Boolean
	def exists(path: String): Boolean
	def mkdirs(path: String): Boolean
	def relativeFreeSpace(path: String): Double
	def purgeEmptyFolders(path: String): Boolean
}
