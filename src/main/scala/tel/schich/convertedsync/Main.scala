package tel.schich.convertedsync

import java.util.Locale

import tel.schich.convertedsync.io.FileInfo


class ConversionException(message: String, val source: FileInfo) extends Exception(message)

object Main extends App {
	Locale.setDefault(Locale.ENGLISH)

	Config.parse(args) match {
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

