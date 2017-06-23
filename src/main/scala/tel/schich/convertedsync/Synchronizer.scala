package tel.schich.convertedsync

import java.nio.file._
import java.util.concurrent.TimeUnit.SECONDS

import org.apache.tika.config.TikaConfig
import tel.schich.convertedsync.Timing.time
import tel.schich.convertedsync.io.{FileInfo, IOAdapter, LocalAdapter, ShellAdapter}
import tel.schich.convertedsync.mime.TikaMimeDetector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Synchronizer {

	val TempSuffix: String = ".temporary"

	private def syncFromTo(conf: Config, local: IOAdapter, remote: IOAdapter): Boolean = {
		println(s"Scanning the source directory: ${conf.source} ...")
		val (sourceFiles, sourceScanTime) = time(SECONDS) {
			local.files(conf.source.toString)
		}
		println(s"Found ${sourceFiles.length} source files in $sourceScanTime seconds.")

		sourceFiles.groupBy(fd => fd.mime).foreach {
			case (group, files) => println(s"$group -> ${files.length}")
		}

		println(s"Scanning the target directory: ${conf.target} ...")
		val (targetFiles, targetScanTime) = time(SECONDS) {

			val files = remote.files(conf.target.toString).map(x => (x.core, x)).toMap

			if (conf.purge) {
				val sourceLookup = sourceFiles.map(_.core).toSet
				files.filter { case (relative, file) =>
					if (!sourceLookup.contains(relative) || conf.purgeDifferentMime && conf.mime != file.mime) {
						remote.delete(file.fullPath)
						println(s"Purged ${file.fullPath} (${file.mime})")
						false
					} else true
				}
			} else files
		}
		println(s"Found ${targetFiles.size} files in the target directory in $targetScanTime seconds.")

		println("Detecting files to be processed...")
		val toProcess = sourceFiles.filter { f =>
			if (targetFiles.contains(f.core)) {
				val target = targetFiles(f.core)
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

		val scriptDir = conf.convertersDir.toRealPath()

		if (conf.threadCount > 0) {
			println(s"Using ${conf.threadCount} thread(s) for the conversion.")
			for (p <- Seq("minThreads", "numThreads", "maxThreads")) {
				System.setProperty(s"scala.concurrent.context.$p", "" + conf.threadCount)
			}
		}

		val futures = toProcess.map { f =>
			// the target path
			val target = conf.target.toString + local.separator + f.core + '.' + conf.extension
			// a temporary target path on the same file system as the target path
			val tmpTarget = target + TempSuffix
			// an intermediate target path on an arbitrary file system
			val intermediateTarget = conf.intermediateDir.map {d =>
				val fileName = f.fileName
				val tempFile = Files.createTempFile(d, "intermediate_", s"_$fileName")
				if (Files.exists(tempFile)) {
					Files.delete(tempFile)
				}
				tempFile.toString
			}.getOrElse(tmpTarget)

			val usingIntermediate = !intermediateTarget.equals(tmpTarget)

			Future {
				val dir = Paths.get(target).getParent
				if (!Files.exists(dir)) {
					Files.createDirectories(dir)
				}
				val fileStore = Files.getFileStore(dir)
				val relativeFreeSpace = fileStore.getUsableSpace / fileStore.getTotalSpace.asInstanceOf[Double]
				println("Free space on target file system: %1.2f%%".format(relativeFreeSpace))
				if (relativeFreeSpace < conf.lowSpaceThreshold) {
					throw new ConversionException(s"The target file system ran out of disk space (free space below ${conf.lowSpaceThreshold}%)", f)
				}

				val fullPath = Paths.get(f.fullPath)

				if (!Files.exists(fullPath)) {
					throw new ConversionException("The file was queued for conversion, but disappeared!", f)
				}

				if (f.mime == conf.mime && !conf.force) {
					println("The input file mime type matches the target mime type, copying...")
					time() {
						local.copy(f.fullPath, intermediateTarget)
					}._2
				} else {
					val t = time() {
						runConverter(scriptDir, f, intermediateTarget, conf.mime, !conf.silenceConverter)
					}._2

					if (!usingIntermediate && !remote.exists(intermediateTarget) ||
						 usingIntermediate && !local.exists(intermediateTarget)) {
						throw new ConversionException(s"Converter did not generate file $intermediateTarget", f)
					}
					t
				}
			}.map { time =>
				if (usingIntermediate) {
					try {
						remote.move(intermediateTarget, tmpTarget)
					} catch {
						case e: Exception if local.exists(intermediateTarget) =>
							local.delete(intermediateTarget)
							throw e
					}
				}
				remote.rename(tmpTarget, target)
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

	def sync(conf: Config): Boolean = {
		val mime = new TikaMimeDetector(TikaConfig.getDefaultConfig, !conf.mimeFromExtension, conf.warnWrongExtension)
		val local = new LocalAdapter(mime)
		resolveRemoteAdapter(conf, local) match {
			case Some(remote) => syncFromTo(conf, local, remote)
			case _ => false
		}
	}

	private def runConverter(scriptDir: Path, sourceFile: FileInfo, targetFile: String, targetMime: String, inheritIO: Boolean): Unit = {
		ShellScript.resolve(scriptDir.resolve(targetMime).resolve(sourceFile.mime), inheritIO) match {
			case Some(script) =>
				val status = script.invoke(sourceFile.fullPath, targetFile)
				if (status != 0) {
					throw new ConversionException(s"Converter for ${sourceFile.mime} was not successful: $status", sourceFile)
				}
			case _ =>
				throw new ConversionException(s"Converter for ${sourceFile.mime} not found!", sourceFile)
		}
	}

	private def resolveRemoteAdapter(conf: Config, localAdapter: LocalAdapter): Option[IOAdapter] = {
		if (LocalAdapter.isLocal(conf.ioAdapter)) Some(localAdapter)
		else {
			val scriptPath = conf.adaptersDir.resolve(conf.ioAdapter)
			ShellScript.resolve(scriptPath) match {
				case Some(script) =>
					val mime = new TikaMimeDetector(TikaConfig.getDefaultConfig, false, conf.warnWrongExtension)
					Some(new ShellAdapter(mime, script, localAdapter.separator))
				case _ => None
			}
		}
	}
}
