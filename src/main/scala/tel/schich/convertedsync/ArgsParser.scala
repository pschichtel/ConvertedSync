package tel.schich.convertedsync

import java.nio.file.{Path, Paths}

import org.apache.tika.mime.MediaType
import scopt.{OptionParser, Read}

case class Config(source: Path, target: Path,
                  scriptDir: Path, mime: String,
                  extension: String, purge: Boolean,
                  purgeDifferentMime: Boolean, force: Boolean,
                  createTarget: Boolean, mimeFromExtension: Boolean,
                  warnWrongExtension: Boolean, threadCount: Int = 0)

object ArgsParser {

	implicit val pathRead: Read[Path] = Read.reads(Paths.get(_))
	implicit val mediaTypeRead: Read[MediaType] = Read.reads(MediaType.parse)

	val defaults = Config(null, null, Paths.get("scripts"), null, null, purge = false, purgeDifferentMime = false, force = false, createTarget = false, mimeFromExtension = false, warnWrongExtension = true)

	val parser = new OptionParser[Config]("ConvertedSync") {

		head("ConvertedSync")

		opt[Path]('s', "source-path") required() valueName "<path>" text "The source path for the synchronization." action { (path, config) =>
			config.copy(source = path)
		}

		opt[Path]('t', "target-path") required() valueName "<path>" text "The target path for the synchronization." action { (path, config) =>
			config.copy(target = path)
		}

		opt[Unit]("create-target") text "Whether to create the target folder." action { (_, config) =>
			config.copy(createTarget = true)
		}

		opt[Path]("script-dir") valueName "<path>" text "The base path of the conversion scripts/programs." action { (path, config) =>
			config.copy(scriptDir = path.toRealPath())
		}

		opt[String]('e', "target-extension") required() valueName "<extension>" text "The file extension newly converted files should have." action { (extension, config) =>
			config.copy(extension = extension)
		}

		opt[MediaType]('m', "target-mime") required() valueName "<mime/type>" text "The target mime-type for the conversion." action { (mime, config) =>
			config.copy(mime = mime.toString)
		}

		opt[Unit]("purge") text "Delete files that are available in the target folder, but not in the source folder." action {(_, config) =>
			config.copy(purge = true)
		}

		opt[Unit]("purge-different-mime") text "Delete files that are available in the target folder, but not in the source folder." action {(_, config) =>
			config.copy(purgeDifferentMime = true)
		}

		opt[Unit]('f', "force") text "Force conversion even if the mime-type of source and target match." action {(_, config) =>
			config.copy(force = true)
		}

		opt[Unit]("mime-from-extension") text "Use only the filename extension for mime detection (may be imprecise)." action {(_, config) =>
			config.copy(mimeFromExtension = true)
		}

		opt[Unit]("no-extension-validation") text "Verify if the filename extension matches the mime type (relies on mime detection precision)." action {(_, config) =>
			config.copy(warnWrongExtension = false)
		}

		opt[Int]('t', "threads") text "The number of threads to use for the conversion process. Limit this to your number of CPU cores unless the target directory is slow." action {(n, config) =>
			val threads =
				if (n <= 0) Runtime.getRuntime.availableProcessors()
				else n
			config.copy(threadCount = threads)
		}

	}

	def parse(args: Seq[String]): Option[Config] = parser.parse(args, defaults)
}
