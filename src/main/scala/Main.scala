import java.lang.ProcessBuilder.Redirect
import java.nio.file._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class ConversionException(message: String, val source: FileDescription) extends Exception(message)

object Main extends App {
	val argsParsed = ArgsParser.parse(args).map(sync).getOrElse(false)

	if (argsParsed) println("Successfully converted all outdated or missing files!")
	else println("An error occurred during the conversion!")

	def sync(conf: Config): Boolean = {
		val sourceDir = conf.source
		val targetDir = conf.target
		val scriptDir = conf.scriptDir.toRealPath()

		val sourceFiles = DirectoryScanner.scanEnriched(sourceDir)
		val targetFiles = {
			val files = DirectoryScanner.scanEnriched(targetDir).map(x => (x.withoutExtension, x)).toMap

			if (conf.purge) {
				val sourceLookup = sourceFiles.map(_.withoutExtension).toSet
				files.filter { case (relative, file) =>
					if (!sourceLookup.contains(relative)) {
						Files.delete(file.fullPath)
						println(s"Purged ${file.fullPath}")
						false
					} else true
				}
			} else files
		}

		val toProcess = sourceFiles.filter { f =>
			if (targetFiles.contains(f.withoutExtension)) {
				val target = targetFiles(f.withoutExtension)
				target.lastModified.compareTo(f.lastModified) < 0
			} else true
		}

		if (!Files.exists(targetDir)) {
			Files.createDirectories(targetDir)
		}

		val futures = toProcess.map { f =>
			val target = targetDir.resolve(f.withoutExtension + "." + conf.extension)

			Future {
				val tmpTarget = target.getParent.resolve(target.getFileName + ".tmp")
				val dir = target.getParent
				if (!Files.exists(dir)) {
					Files.createDirectories(dir)
				}
				if (f.mime == conf.mime) {
					Files.copy(f.fullPath, tmpTarget)
					tmpTarget
				} else {
					val script = scriptDir.resolve(conf.mime).resolve(f.mime)
					if (Files.isExecutable(script)) {
						val pb = new ProcessBuilder()
						pb.command(script.toString, f.fullPath.toString, tmpTarget.toString)
						pb.redirectInput(Redirect.INHERIT)
						pb.redirectOutput(Redirect.INHERIT)
						pb.redirectError(Redirect.INHERIT)
						val process = pb.start()
						process.waitFor() match {
							case 0 => tmpTarget
							case status => throw new ConversionException(s"Converter for ${f.mime} was not successful: $status", f)
						}
					} else {
						throw new ConversionException(s"Converter for ${f.mime} not found: $script", f)
					}
				}
			}.map { tmp =>
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
				println(s"Conversion complete for: ${f.fullPath}")
				println(s"    Now at: $target")
				target
			} recover {
				case e: ConversionException =>
					println("Conversion failed for: " + f.fullPath)
					println("    Error: " + e.getMessage)
					null
			}
		}

		val result = Await.result(Future.sequence(futures), Duration.Inf)
		println("Done! Converted files:")
		true
	}
}

