package tel.schich.convertedsync

import java.nio.file._
import java.util.concurrent.TimeUnit.SECONDS

import org.apache.tika.config.TikaConfig
import tel.schich.convertedsync.Timing.time
import tel.schich.convertedsync.io.{FileInfo, IOAdapter, LocalAdapter, ShellAdapter}
import tel.schich.convertedsync.mime.TikaMimeDetector

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Synchronizer {

	val TempSuffix: String = ".temporary"

	private def syncFromTo(conf: Config, local: IOAdapter, remote: IOAdapter): Boolean = {

		if (!remote.exists(conf.target)) {
			println("Target directory does not exist!")
			System.exit(1)
		}

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

		if (conf.threadCount > 0) {
			println(s"Using ${conf.threadCount} thread(s) for the conversion.")
			for (p <- Seq("minThreads", "numThreads", "maxThreads")) {
				System.setProperty(s"scala.concurrent.context.$p", "" + conf.threadCount)
			}
		}

		val converters = findConverters(conf, local, !conf.silenceConverter)

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

			val intermediateAdapter =
				if (intermediateTarget.equals(tmpTarget)) remote
				else local

			Future {
				val dir = Util.parentPath(target, remote.separator)
				if (!remote.exists(dir)) {
					remote.mkdirs(dir)
				}

				val relativeFreeSpace = remote.relativeFreeSpace(dir)
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
						intermediateAdapter.copy(f.fullPath, intermediateTarget)
					}._2
				} else {
					val t = time() {
						runConverter(conf, f, intermediateTarget, converters)
					}._2

					if (!intermediateAdapter.exists(intermediateTarget)) {
						throw new ConversionException(s"Converter did not generate file $intermediateTarget", f)
					}
					t
				}
			}.map { time =>
				if (intermediateAdapter == local) {
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

		if (conf.purge) {
			println("Purge empty directories...")
			remote.purgeEmptyFolders(conf.target)
		}

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

	private def findConverters(conf: Config, local: IOAdapter, inheritIO: Boolean): Map[String, ShellScript] = {
		val scripts = Files.walk(conf.convertersDir).iterator().asScala.filter(Files.isExecutable).flatMap { path =>
			val relativePath = conf.convertersDir.relativize(path).toString
			relativePath.split(local.separator).toSeq match {
				case Seq(a, b, c, d) =>
					Some((s"$a/$b/$c/$d", ShellScript(path, inheritIO)))
				case _ =>
					None
			}
		}
		scripts.toMap
	}

	private def runConverter(conf: Config, sourceFile: FileInfo, targetFile: String, converters: Map[String, ShellScript]): Unit = {
		lookupScript(converters, sourceFile.mime, conf.mime, conf.fallbackMime) match {
			case Some(script) =>
				val status = script.invoke(Array(sourceFile.fullPath, targetFile))
				if (status != 0) {
					throw new ConversionException(s"Converter for ${sourceFile.mime} was not successful: $status", sourceFile)
				}
			case _ =>
				throw new ConversionException(s"Converter for ${sourceFile.mime} not found!", sourceFile)
		}
	}

	private def lookupScript(converters: Map[String, ShellScript], sourceMime: String, targetMime: String, fallbackMime: String): Option[ShellScript] = {
		Seq(s"$sourceMime/$targetMime", s"$fallbackMime/$targetMime", s"$fallbackMime/$fallbackMime")
			.find(converters.isDefinedAt)
			.map(converters)
	}

	private def resolveRemoteAdapter(conf: Config, localAdapter: LocalAdapter): Option[IOAdapter] = {
		if (LocalAdapter.isLocal(conf.adapter)) Some(localAdapter)
		else {
			val scriptPath = conf.adaptersDir.resolve(conf.adapter)
			ShellScript.resolve(scriptPath) match {
				case Some(script) =>
					val mime = new TikaMimeDetector(TikaConfig.getDefaultConfig, false, conf.warnWrongExtension)
					Some(new ShellAdapter(mime, script, localAdapter.separator))
				case _ => None
			}
		}
	}
}
