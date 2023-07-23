package tel.schich.convertedsync

import java.util.Locale

import tel.schich.convertedsync.io.FileInfo

import scala.collection.immutable.ArraySeq


class ConversionException(message: String, val source: FileInfo) extends Exception(message)

object Main {
	def main(args: Array[String]): Unit = {
		Locale.setDefault(Locale.ENGLISH)

		Config.parse(ArraySeq.unsafeWrapArray(args)) match {
			case Some(conf) =>
				if (Synchronizer.sync(conf)) {
					println("Successfully converted all outdated or missing files!")
				} else {
					println("An error occurred during the conversion!")
				}
			case _ =>
				println("Failed to parse the input argument")
		}
	}
}
