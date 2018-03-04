package tel.schich.convertedsync

import java.nio.file._
import java.util.concurrent.TimeUnit.SECONDS

import org.apache.tika.config.TikaConfig
import tel.schich.convertedsync.ConversionRule.findRule
import tel.schich.convertedsync.Timing.time
import tel.schich.convertedsync.io.{FileInfo, IOAdapter, LocalAdapter, ShellAdapter}
import tel.schich.convertedsync.mime.TikaMimeDetector

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

			val existingFiles = remote.files(conf.target.toString).map(x => (x.core, x)).toMap

			if (conf.purge) {
				val sourceLookup = sourceFiles.map(f => (f.core, f)).toMap
				existingFiles.filter { case (core, existingFile) =>
					if (!sourceLookup.contains(core) || conf.purgeDifferentMime && conflictingMimes(conf.rules, sourceLookup(core), existingFile)) {
						remote.delete(existingFile.fullPath)
						println(s"Purged ${existingFile.fullPath} (${existingFile.mime})")
						false
					} else true
				}
			} else existingFiles
		}

		println(s"Found ${targetFiles.size} files in the target directory in $targetScanTime seconds.")

		val toProcess =
			if (conf.reEncodeAll) sourceFiles
			else {
				println("Detecting files to be processed...")
				sourceFiles.filter { f =>
					if (targetFiles.contains(f.core)) {
						val target = targetFiles(f.core)
						target.lastModified.compareTo(f.lastModified) < 0
					} else true
				}
			}

		if (conf.threadCount > 0) {
			println(s"Using ${conf.threadCount} thread(s) for the conversion.")
			for (p <- Seq("minThreads", "numThreads", "maxThreads")) {
				System.setProperty(s"scala.concurrent.context.$p", "" + conf.threadCount)
			}
		}

		val convertibleFiles = toProcess.flatMap { f =>
			findRule(f.mime, conf.rules) match {
				case Some(rule) =>
					Some((f, rule))
				case None =>
					println(s"No applicable conversion rule for file: ${f.fullPath} (${f.mime})")
					None
			}
		}

		println(s"${convertibleFiles.length} source files will be synchronized to the target folder.")

		val futures = convertibleFiles.map((convert(conf, local, remote) _).tupled)

		Await.result(Future.sequence(futures), Duration.Inf)

		if (conf.purge) {
			println("Purge empty directories...")
			remote.purgeEmptyFolders(conf.target)
		}

		println("Done!")
		true
	}

	def convert(conf: Config, local: IOAdapter, remote: IOAdapter)(f: FileInfo, rule: ConversionRule): Future[String] = {

		// the target path
		val target = conf.target.toString + local.separator + f.core + '.' + rule.extension
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

			if (f.mime == rule.targetMime && !conf.force) {
				println("The input file mime type matches the target mime type, copying...")
				time() {
					intermediateAdapter.copy(f.fullPath, intermediateTarget)
				}._2
			} else {
				val t = time() {
					runConverter(conf, f, intermediateTarget, rule)
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

	def sync(conf: Config): Boolean = {
		val mime = new TikaMimeDetector(TikaConfig.getDefaultConfig, !conf.mimeFromExtension, conf.warnWrongExtension)
		val local = new LocalAdapter(mime)
		resolveRemoteAdapter(conf, local) match {
			case Some(remote) =>
				syncFromTo(conf, local, remote)
			case _ =>
				System.err.println("The given IO adapter could not be resolved.")
				false
		}
	}

	private def conflictingMimes(rules: Seq[ConversionRule], sourceFile: FileInfo, existingFile: FileInfo) = {
		findRule(sourceFile.mime, rules)
			.forall(r => !r.targetMime.equalsIgnoreCase(existingFile.mime))
	}

	private def runConverter(conf: Config, sourceFile: FileInfo, targetFile: String, rule: ConversionRule): Unit = {

		ShellScript.resolve(conf.convertersDir.resolve(rule.converter), !conf.silenceConverter) match {
			case Some(script) =>
				println(s"Applying converter: ${script.executable}")
				val status = script.invoke(Array(sourceFile.fullPath, targetFile))
				if (status != 0) {
					throw new ConversionException(s"Converter for ${sourceFile.mime} was not successful: $status", sourceFile)
				}
			case _ =>
				throw new ConversionException(s"Converter for ${sourceFile.mime} not found!", sourceFile)
		}
	}

	private def resolveRemoteAdapter(conf: Config, localAdapter: LocalAdapter): Option[IOAdapter] = {
		conf.adapter match {
			case Some(path) =>
				ShellScript.resolve(path) match {
					case Some(script) =>
						val mime = new TikaMimeDetector(TikaConfig.getDefaultConfig, false, conf.warnWrongExtension)
						Some(new ShellAdapter(mime, script, localAdapter.separator))
					case _ => None
				}
			case None => Some(localAdapter)
		}
	}
}
