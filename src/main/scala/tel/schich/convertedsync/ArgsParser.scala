package tel.schich.convertedsync

import java.nio.file.{Files, Path, Paths}

import org.apache.tika.mime.MediaType
import scopt.{OptionParser, Read}
import tel.schich.convertedsync.io.LocalAdapter
import tel.schich.convertedsync.io.LocalAdapter.isLocal

case class Config(source: Path, target: String,
                  convertersDir: Path, mime: String,
                  extension: String, purge: Boolean,
                  purgeDifferentMime: Boolean, force: Boolean,
                  mimeFromExtension: Boolean, warnWrongExtension: Boolean,
                  threadCount: Int, intermediateDir: Option[Path],
                  silenceConverter: Boolean, lowSpaceThreshold: Double,
                  adapter: String, adaptersDir: Path)

object ArgsParser {

	implicit val pathRead: Read[Path] = Read.reads(Paths.get(_))
	implicit val mediaTypeRead: Read[MediaType] = Read.reads(MediaType.parse)

	val defaults = Config(
		null, null,
		Paths.get("converters"), null,
		null, purge = false,
		purgeDifferentMime = false, force = false,
		mimeFromExtension = false, warnWrongExtension = true,
		threadCount = 0, intermediateDir = None,
		silenceConverter = false, lowSpaceThreshold = 0,
		adapter = LocalAdapter.name, adaptersDir = Paths.get("adapters")
	)

	val parser = new OptionParser[Config]("ConvertedSync") {

		head("ConvertedSync")

		opt[Path]('s', "source-path") required() valueName "<path>" text "The source path for the synchronization." action { (path, config) =>
			config.copy(source = path)
		}

		opt[String]('t', "target-path") required() valueName "<path>" text "The target path for the synchronization." action { (path, config) =>
			config.copy(target = path)
		}

		opt[Path]("converters-dir") valueName "<path>" text "The base path of the conversion programs." action { (path, config) =>
			config.copy(convertersDir = path.toRealPath())
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

		opt[Unit]("purge-different-mime") text "Also delete files in the target folder, that don't have the expected mime type." action {(_, config) =>
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

		opt[Int]('t', "threads") valueName "<# threads>" text "The number of threads to use for the conversion process. Limit this to your number of CPU cores unless the target directory is slow." action {(n, config) =>
			config.copy(threadCount = n)
		}

		opt[Path]("intermediate-dir") valueName "<path>" text "Set this in case the target path can not be directly converted to." action {(path, config) =>
			config.copy(intermediateDir = Some(path))
		}

		opt[Unit]('q', "silence-converter") text "Don't forward the output of the conversion processes." action {(_, config) =>
			config.copy(silenceConverter = true)
		}

		opt[Int]("low-disk-space-threshold") text "The free disk space percentage that may not be used for synced files (integer in [0, 100])" action {(i, config) =>
			config.copy(lowSpaceThreshold = i / 100d)
		}

		opt[String]("io-adapter") text "Use the given IO adapter script" action {(adapter, conf) =>
			conf.copy(adapter = adapter)
		}

		opt[Path]("adapters-dir") valueName "<path>" text "The base path of the conversion programs." action {(path, conf) =>
			conf.copy(adaptersDir = path)
		}

		checkConfig { config =>
			if (config.intermediateDir.isDefined && !Files.isDirectory(config.intermediateDir.get))
				failure("The intermediate directory must exist!")
			else if (config.threadCount < 0)
				failure("A negative amount of threads is not possible!")
			else if (config.lowSpaceThreshold < 0 || config.lowSpaceThreshold > 1)
				failure("The free space threshold may not be lower than 0% or higher than 100%.")
			else if (!isLocal(config.adapter) && (!config.mimeFromExtension || config.intermediateDir.isEmpty))
				failure("IO adapters require file extension based mime detection and an intermediate directory!")
			else
				success
		}
	}

	def parse(args: Seq[String]): Option[Config] = parser.parse(args, defaults)
}
