package tel.schich.convertedsync

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit.SECONDS

import tel.schich.convertedsync.Timing._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Synchronizer {


	def sync(conf: Config): Boolean = {
		val directoryScanner = new DirectoryScanner()

		println(s"Scanning the source directory: ${conf.source} ...")
		val (sourceFiles, sourceScanTime) = time(SECONDS) {
			directoryScanner.scanEnriched(conf.source, allowFileAccess = !conf.mimeFromExtension, warnWrongExtension = conf.warnWrongExtension)
		}
		println(s"Found ${sourceFiles.length} source files in $sourceScanTime seconds.")

		sourceFiles.groupBy(fd => fd.mime).foreach {
			case (mime, files) => println(s"$mime -> ${files.length}")
		}

		System.exit(1)

		println(s"Scanning the target directory: ${conf.target} ...")
		val (targetFiles, targetScanTime) = time(SECONDS) {
			val files = directoryScanner.scanEnriched(conf.target, conf.purgeDifferentMime, allowFileAccess = !conf.mimeFromExtension).map(x => (x.withoutExtension, x)).toMap

			if (conf.purge) {
				val sourceLookup = sourceFiles.map(_.withoutExtension).toSet
				files.filter { case (relative, file) =>
					if (!sourceLookup.contains(relative) || conf.purgeDifferentMime && conf.mime != file.mime) {
						Files.delete(file.fullPath)
						println(s"Purged ${file.fullPath} (${file.mime})")
						false
					} else true
				}
			} else files
		}
		println(s"Found ${targetFiles.size} files in the target directory in $targetScanTime seconds.")

		println("Detecting files to be processed...")
		val toProcess = sourceFiles.filter { f =>
			if (targetFiles.contains(f.withoutExtension)) {
				val target = targetFiles(f.withoutExtension)
				target.lastModified.compareTo(f.lastModified) < 0
			} else true
		}
		println(s"${toProcess.length} source files will be synchronized to the target folder.")

		if (!Files.exists(conf.target)) {
			if (conf.createTarget) {
				Files.createDirectories(conf.target)
			} else {
				println("Target directory does not exist!")
				System.exit(1)
			}
		}

		val scriptDir = conf.scriptDir.toRealPath()

		val futures = toProcess.map { f =>
			val target = conf.target.resolve(f.withoutExtension + "." + conf.extension)
			val tmpTarget = target.getParent.resolve(s".${target.getFileName}.temporary")

			Future {
				val dir = target.getParent
				if (!Files.exists(dir)) {
					Files.createDirectories(dir)
				}
				if (!Files.exists(f.fullPath)) {
					throw new ConversionException("The file was queued for conversion, but disappeared!", f)
				}
				if (f.mime == conf.mime && !conf.force) {
					println("The input file mime type matches the target mime type, copying...")
					time() {
						Files.copy(f.fullPath, tmpTarget)
					}._2
				} else {
					val t = time() {
						runScript(scriptDir, f, tmpTarget, conf.mime)
					}._2

					if (!Files.exists(tmpTarget)) {
						throw new ConversionException(s"Converter did not generate file $tmpTarget", f)
					}
					t
				}
			}.map { time =>
				Files.move(tmpTarget, target, StandardCopyOption.ATOMIC_MOVE)
				println(s"Conversion completed after ${time}ms: ${f.fullPath}\n"
					  + s"    Now at: $target")
				target
			} recover {
				case e: ConversionException =>
					println(s"Conversion failed for: ${f.fullPath}\n"
						  + s"    Error: ${e.getMessage}")
					null
			}
		}

		Await.result(Future.sequence(futures), Duration.Inf)
		println("Done!")
		true
	}

	def runScript(scriptDir: Path, sourceFile: FileDescription, targetFile: Path, targetMime: String): Unit = {
		val possibleScripts = constructScripts(scriptDir, sourceFile.mime, targetMime).filter(Files.isExecutable)
		if (possibleScripts.nonEmpty) {
			val pb = new ProcessBuilder()
			pb.command(possibleScripts.head.toString, sourceFile.fullPath.toString, targetFile.toString)
			pb.redirectInput(Redirect.INHERIT)
			pb.redirectOutput(Redirect.INHERIT)
			pb.redirectError(Redirect.INHERIT)
			val process = pb.start()
			val status = process.waitFor()
			if (status != 0) {
				throw new ConversionException(s"Converter for ${sourceFile.mime} was not successful: $status", sourceFile)
			}
		} else {
			throw new ConversionException(s"Converter for ${sourceFile.mime} not found!", sourceFile)
		}
	}

	def constructScripts(scriptDir: Path, fromMime: String, toMime: String): Seq[Path] = {
		val script = scriptDir.resolve(toMime).resolve(fromMime)

		val pathExt = sys.env.getOrElse("PATHEXT", "")
		if (pathExt.length > 0) {
			val parent = script.getParent
			val name = script.getFileName
			pathExt.split(File.pathSeparatorChar).map(ext => parent.resolve(name + ext)) :+ script
		} else List(script)
	}

}
