package tel.schich.convertedsync

import java.util.Locale


class ConversionException(message: String, val source: FileDescription) extends Exception(message)

object Main extends App {
	Locale.setDefault(Locale.ENGLISH)

	val argsParsed = ArgsParser.parse(args).map(Synchronizer.sync).getOrElse(false)

	if (argsParsed) println("Successfully converted all outdated or missing files!")
	else println("An error occurred during the conversion!")
}

