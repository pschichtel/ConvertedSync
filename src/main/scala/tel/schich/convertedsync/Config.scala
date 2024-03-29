package tel.schich.convertedsync

import java.nio.file.{Files, Path, Paths}

import org.apache.tika.mime.MediaType
import scopt.{OptionParser, Read}

case class Config(source: Path, target: String,
                  convertersDir: Path, purge: Boolean,
                  enforceMime: Boolean, force: Boolean,
                  mimeFromExtension: Boolean, warnWrongExtension: Boolean,
                  threadCount: Int, intermediateDir: Option[Path],
                  silenceConverter: Boolean, lowSpaceThreshold: Double,
                  adapter: Option[Path], rules: Seq[ConversionRule],
                  reEncodeAll: Boolean)

object Config {

	implicit val pathRead: Read[Path] = Read.reads(Paths.get(_))
	implicit val mediaTypeRead: Read[MediaType] = Read.reads(MediaType.parse)
	implicit val conversionRuleRead: Read[ConversionRule] = Read.reads { s =>
		ConversionRule.parse(s) match {
			case Some(rule) => rule
			case None => throw new IllegalArgumentException("'" + s + "' is not a valid conversion rule.")
		}
	}

	val defaults: Config = Config(
		null, null,
		Paths.get("converters"), purge = false,
		enforceMime = false, force = false,
		mimeFromExtension = false, warnWrongExtension = true,
		threadCount = 0, intermediateDir = None,
		silenceConverter = false, lowSpaceThreshold = 0,
		adapter = None, rules = Seq.empty,
		reEncodeAll = false
	)

	val parser: OptionParser[Config] = new OptionParser[Config]("ConvertedSync") {

		head("ConvertedSync")

		opt[Path]('s', "source-path").required().valueName("<path>").text("The source path for the synchronization.").action { (path, config) =>
			config.copy(source = path)
		}

		opt[String]('t', "target-path").required().valueName("<path>").text("The target path for the synchronization.").action { (path, config) =>
			config.copy(target = path)
		}

		opt[Unit]("re-encode-all").text("Re-encode already existing files in the target directory.").action { (_, config) =>
			config.copy(reEncodeAll = true)
		}

		opt[Path]("converters-dir").valueName("<path>").text("The base path of the conversion programs.").action { (path, config) =>
			config.copy(convertersDir = path.toRealPath())
		}

		opt[Unit]("purge").text("Delete files that are available in the target folder, but not in the source folder.").action { (_, config) =>
			config.copy(purge = true)
		}

		opt[Unit]("enforce-mime").text("Re-encode files that are unchanged, but don't have the requested mime type.").action { (_, config) =>
			config.copy(enforceMime = true)
		}

		opt[Unit]("force").text("Force conversion even if the mime-type of source and target match.").action { (_, config) =>
			config.copy(force = true)
		}

		opt[Unit]("mime-from-extension").text("Use only the filename extension for mime detection (may be imprecise).").action { (_, config) =>
			config.copy(mimeFromExtension = true)
		}

		opt[Unit]("no-extension-validation").text("Verify if the filename extension matches the mime type (relies on mime detection precision).").action { (_, config) =>
			config.copy(warnWrongExtension = false)
		}

		opt[Int]('t', "threads").valueName("<# threads>").text("The number of threads to use for the conversion process. Limit this to your number of CPU cores unless the target directory is slow.").action { (n, config) =>
			config.copy(threadCount = n)
		}

		opt[Path]("intermediate-dir").valueName("<path>").text("Set this in case the target path can not be directly converted to.").action { (path, config) =>
			config.copy(intermediateDir = Some(path))
		}

		opt[Unit]('q', "silence-converter").text("Don't forward the output of the conversion processes.").action { (_, config) =>
			config.copy(silenceConverter = true)
		}

		opt[Int]("low-disk-space-threshold").text("The free disk space percentage that may not be used for synced files (integer in [0, 100])").action { (i, config) =>
			config.copy(lowSpaceThreshold = i / 100d)
		}

		opt[Path]("io-adapter").valueName("<path>").text("Use the given IO adapter executable. This option implies --mime-from-extension.").action { (adapter, conf) =>
			conf.copy(adapter = Some(adapter), mimeFromExtension = true)
		}

		opt[ConversionRule]("rule").minOccurs(1).unbounded().valueName("<source mime:target mime:extension:converter>").text("Defines a conversion rule by source mime, conversion script and target extension.").action { (rule, conf) =>
			conf.copy(rules = conf.rules :+ rule)
		}

		checkConfig { conf =>
			if (conf.intermediateDir.isDefined && !Files.isWritable(conf.intermediateDir.get))
				failure("The intermediate directory must exist and must be writable!")
			else if (conf.threadCount < 0)
				failure("A negative amount of threads is not possible!")
			else if (conf.lowSpaceThreshold < 0 || conf.lowSpaceThreshold > 1)
				failure("The free space threshold may not be lower than 0% or higher than 100%.")
			else if (conf.adapter.nonEmpty && conf.intermediateDir.isEmpty)
				failure("IO adapters require an intermediate directory!")
			else if (!Files.isDirectory(conf.convertersDir))
				failure("The converters directory does not exist!")
			else
				success
		}
	}

	def parse(args: Seq[String]): Option[Config] = parser.parse(args, defaults)
}
