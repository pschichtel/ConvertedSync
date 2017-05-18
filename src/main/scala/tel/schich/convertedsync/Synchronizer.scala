package tel.schich.convertedsync

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.nio.file._
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

		if (conf.threadCount > 0) {
			println(s"Using ${conf.threadCount} thread(s) for the conversion.")
			for (p <- Seq("minThreads", "numThreads", "maxThreads")) {
				System.setProperty(s"scala.concurrent.context.$p", "" + conf.threadCount)
			}
		}

		val futures = toProcess.map { f =>
			// the target path
			val target = conf.target.resolve(f.withoutExtension + "." + conf.extension)
			// a temporary target path on the same file system as the target path
			val tmpTarget = target.getParent.resolve(s".${target.getFileName}.temporary")
			// an intermediate target path on an arbitrary file system
			val intermediateTarget = conf.intermediateDir.map {d =>
				val fileName = f.fullPath.getFileName.toString
				val tempFile = Files.createTempFile(d, "intermediate_", s"_$fileName")
				if (Files.exists(tempFile)) {
					Files.delete(tempFile)
				}
				tempFile
			}.getOrElse(tmpTarget)

			Future {
				val dir = target.getParent
				if (!Files.exists(dir)) {
					Files.createDirectories(dir)
				}
				val fileStore = Files.getFileStore(dir)
				val relativeFreeSpace = fileStore.getUsableSpace / fileStore.getTotalSpace.asInstanceOf[Double]
				println("Free space: %1.2f%%".format(relativeFreeSpace))
				if (relativeFreeSpace < conf.lowSpaceThreshold) {
					throw new ConversionException(s"The target file system ran out of disk space (free space below ${conf.lowSpaceThreshold}%)", f)
				}

				if (!Files.exists(f.fullPath)) {
					throw new ConversionException("The file was queued for conversion, but disappeared!", f)
				}

				if (f.mime == conf.mime && !conf.force) {
					println("The input file mime type matches the target mime type, copying...")
					time() {
						Files.copy(f.fullPath, intermediateTarget)
					}._2
				} else {
					val t = time() {
						runScript(scriptDir, f, intermediateTarget, conf.mime, !conf.silenceConverter)
					}._2

					if (!Files.exists(intermediateTarget)) {
						throw new ConversionException(s"Converter did not generate file $intermediateTarget", f)
					}
					t
				}
			}.map { time =>
				if (!intermediateTarget.equals(tmpTarget)) {
					try {
						moveFile(intermediateTarget, tmpTarget)
					} catch {
						case e: Exception if Files.exists(intermediateTarget) =>
							Files.delete(intermediateTarget)
							throw e
					}
				}
				moveFile(tmpTarget, target)
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

	def runScript(scriptDir: Path, sourceFile: FileDescription, targetFile: Path, targetMime: String, forwardIO: Boolean): Unit = {
		val possibleScripts = constructScripts(scriptDir, sourceFile.mime, targetMime).filter(Files.isExecutable)
		if (possibleScripts.nonEmpty) {
			val pb = new ProcessBuilder()
			pb.command(possibleScripts.head.toString, sourceFile.fullPath.toString, targetFile.toString)
			if (forwardIO) {
				pb.redirectInput(Redirect.INHERIT)
				pb.redirectOutput(Redirect.INHERIT)
				pb.redirectError(Redirect.INHERIT)
			}
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

	private def moveFile(from: Path, to: Path): Unit = {
		try {
			// Try an atomic move (rename in Linux) operation
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
		} catch {
			case _: AtomicMoveNotSupportedException =>
				try {
					// Try a simple move operation (move in Linux)
					Files.move(from, to)
				} catch {
					case _: FileSystemException =>
						// Copy the source and delete it afterwards if successful
						Files.copy(from, to)
						Files.delete(from)
				}
		}
	}

}
