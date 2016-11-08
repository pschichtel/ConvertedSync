import java.nio.file.{Path, Paths}

import org.apache.tika.mime.MediaType
import scopt.{OptionParser, Read}

case class Config(source: Path, target: Path, scriptDir: Path, mime: String, extension: String, purge: Boolean)

object ArgsParser {

	implicit val pathRead: Read[Path] = Read.reads(Paths.get(_))
	implicit val mediaTypeRead: Read[MediaType] = Read.reads(MediaType.parse)

	val defaults = Config(null, null, Paths.get("scripts"), null, null, true)

	val parser = new OptionParser[Config]("ConvertedSync") {

		head("ConvertedSync", "Stuff")

		opt[Path]('s', "source-path") required() action { (path, config) =>
			config.copy(source = path)
		}

		opt[Path]('t', "target-path") required() action { (path, config) =>
			config.copy(target = path)
		}

		opt[Path]("script-dir") action { (path, config) =>
			config.copy(scriptDir = path.toRealPath())
		}

		opt[String]('e', "target-extension") required() action { (extension, config) =>
			config.copy(extension = extension)
		}

		opt[MediaType]('m', "target-mime") required() action { (mime, config) =>
			config.copy(mime = mime.toString)
		}

		opt[Unit]("purge") action {(_, config) =>
			config.copy(purge = true)
		}

	}

	def parse(args: Seq[String]) = parser.parse(args, defaults)
}
