package tel.schich.convertedsync.io

class ReadonlyProxy(slave: IOAdapter, succeedWrite: Boolean = false) extends IOAdapter {

	private def logAndReturn(method: String, args: Any*): Boolean = {
		val argsStr = args.map {
			case a: String => '"' + a + '"'
			case a => a.toString
		}.mkString(", ")

		println(slave.getClass.getSimpleName + '.' + method + '(' + argsStr + ") = " + succeedWrite)
		succeedWrite
	}

	override def separator: Char =
		slave.separator

	override def files(base: String): IndexedSeq[FileInfo] =
		slave.files(base)

	override def updatePreviousCore(path: String, previousCore: String): Boolean =
		logAndReturn("updatePreviousCore", path, previousCore)

	override def delete(file: String): Boolean =
		logAndReturn("delete", file)

	override def copy(from: String, to: String): Boolean =
		logAndReturn("copy", from, to)

	override def move(from: String, to: String): Boolean =
		logAndReturn("move", from, to)

	override def rename(from: String, to: String): Boolean =
		logAndReturn("rename", from, to)

	override def exists(path: String): Boolean =
		slave.exists(path)

	override def mkdirs(path: String): Boolean =
		logAndReturn("mkdirs", path)

	override def relativeFreeSpace(path: String): Double =
		slave.relativeFreeSpace(path)

	override def purgeEmptyFolders(path: String): Boolean =
		logAndReturn("purgeEmptyFolders", path)
}
