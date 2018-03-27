package tel.schich.convertedsync.io

class ReadonlyProxy(slave: IOAdapter, succeedWrite: Boolean = false) extends IOAdapter {
	override def separator: Char = slave.separator

	override def files(base: String): Seq[FileInfo] = slave.files(base)

	override def updatePreviousCore(path: String, previousCore: String): Boolean = succeedWrite

	override def delete(file: String): Boolean = succeedWrite

	override def copy(from: String, to: String): Boolean = succeedWrite

	override def move(from: String, to: String): Boolean = succeedWrite

	override def rename(from: String, to: String): Boolean = succeedWrite

	override def exists(path: String): Boolean = slave.exists(path)

	override def mkdirs(path: String): Boolean = succeedWrite

	override def relativeFreeSpace(path: String): Double = slave.relativeFreeSpace(path)

	override def purgeEmptyFolders(path: String): Boolean = succeedWrite
}
