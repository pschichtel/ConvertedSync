package tel.schich.convertedsync.io

import java.nio.file.Path

trait IOAdapter {
	def separator: Char
	def files(base: String): Seq[FileInfo]
	def delete(file: String): Boolean
	def copy(from: String, to: String): Boolean
	def move(from: String, to: String): Boolean
	def rename(from: String, to: String): Boolean
	def exists(path: String): Boolean
}
