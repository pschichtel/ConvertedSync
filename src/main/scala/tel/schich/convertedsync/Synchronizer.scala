package tel.schich.convertedsync

import java.nio.file._
import java.util.concurrent.TimeUnit.SECONDS

import org.apache.tika.config.TikaConfig
import tel.schich.convertedsync.ConversionRule.findRule
import tel.schich.convertedsync.Timing.time
import tel.schich.convertedsync.io._
import tel.schich.convertedsync.mime.TikaMimeDetector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object Synchronizer {

	val TempSuffix: String = ".temporary"

	case class ConvertibleFile(file: FileInfo, rule: ConversionRule)

	private def syncFromTo(conf: Config, local: IOAdapter, remote: IOAdapter): Boolean = {

		if (!remote.exists(conf.target)) {
			println("Target directory does not exist!")
			System.exit(1)
		}

		println(s"Scanning the source directory: ${conf.source} ...")
		val (sourceFiles, sourceScanTime) = time(SECONDS) {
			local.files(conf.source.toString).flatMap { f =>
				findRule(f.mime, conf.rules) match {
					case Some(rule) =>
						Some(ConvertibleFile(f, rule))
					case None =>
						println(s"No applicable conversion rule for file: ${f.fullPath} (${f.mime})")
						None
				}
			}
		}
		println(s"Found ${sourceFiles.length} source files in $sourceScanTime seconds.")

		println("File type distribution:")
		sourceFiles.groupBy(cf => cf.file.mime).foreach {
			case (group, files) => println(s"\t$group -> ${files.length}")
		}

		println(s"Scanning the target directory: ${conf.target} ...")
		val (targetFiles, targetScanTime) = time(SECONDS) {
			remote.files(conf.target.toString)
		}
		println(s"Found ${targetFiles.size} files in the target directory in $targetScanTime seconds.")
		val targetLookup = targetFiles.map(x => (x.core, x)).toMap

		val (filesToProcess, filesToRename, validFiles) = if (conf.reEncodeAll) (sourceFiles, Nil, Nil)
		else {
			println("Detecting files to be processed ...")
			val (process, dontProcess) = sourceFiles.partition { f =>
				targetLookup.get(f.file.core).fold(true) { target =>
					target.lastModified.compareTo(f.file.lastModified) < 0
				}
			}

			println("Detecting files to be renamed ...")
			val (rename, valid) = dontProcess.partition { f =>
				f.file.previousCore.flatMap(targetLookup.get).fold(false) { target =>
					target.lastModified.compareTo(f.file.lastModified) < 0
				}
			}
			(process, rename, valid)
		}

		if (conf.purge) {
			println("Purging obsolete files from the target directory ...")
			val handledFiles = (validFiles ++ filesToProcess).map(_.file.core).toSet ++ filesToRename.flatMap(_.file.previousCore)
			val filesToPurge = targetFiles.filterNot(f => handledFiles.contains(f.core))

			for (f <- filesToPurge) {
				remote.delete(f.fullPath)
				println(s"Purged ${f.fullPath} (${f.mime})")
			}
		}

		for {
			f <- filesToRename
			previousCore <- f.file.previousCore
			target <- targetLookup.get(previousCore)
		} {
			if (remote.rename(target.fullPath, f.file.reframeCore(target.base, target.extension))) {
				local.updatePreviousCore(f.file.fullPath, f.file.core)
			}
		}

		if (conf.threadCount > 0) {
			println(s"Using ${conf.threadCount} thread(s) for the conversion.")
			Seq("min", "num", "max")
		    	.map(p => s"scala.concurrent.context.${p}Threads")
		    	.foreach(System.setProperty(_: String, "" + conf.threadCount))
		}

		println(s"${filesToProcess.length} source files will be synchronized to the target folder.")

		val futures = filesToProcess.map(convert(conf, local, remote))

		Await.result(Future.sequence(futures), Duration.Inf)

		if (conf.purge) {
			println("Purge empty directories...")
			remote.purgeEmptyFolders(conf.target)
		}

		println("Done!")
		true
	}

	def convert(conf: Config, local: IOAdapter, remote: IOAdapter)(file: ConvertibleFile): Future[String] = {

		val ConvertibleFile(f, rule) = file
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
			local.updatePreviousCore(f.fullPath, f.core)
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

	private def debugAdapter(adapter: IOAdapter): IOAdapter =
		sys.env.get("DEBUG_IO").fold(adapter) { v =>
			println(s"Making adapter readonly: ${adapter.getClass}")
			new ReadonlyProxy(adapter, v.toBoolean)
		}

	def sync(conf: Config): Boolean = {
		val mime = new TikaMimeDetector(TikaConfig.getDefaultConfig, !conf.mimeFromExtension, conf.warnWrongExtension)
		val local = debugAdapter(new LocalAdapter(mime))
		resolveRemoteAdapter(conf, local) match {
			case Some(remote) =>
				syncFromTo(conf, local, debugAdapter(remote))
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

	private def resolveRemoteAdapter(conf: Config, localAdapter: IOAdapter): Option[IOAdapter] = {
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
