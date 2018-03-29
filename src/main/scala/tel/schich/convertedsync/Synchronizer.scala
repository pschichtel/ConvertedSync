package tel.schich.convertedsync

import java.nio.file._
import java.util.concurrent.TimeUnit.SECONDS

import org.apache.tika.config.TikaConfig
import tel.schich.convertedsync.ConversionRule.findRule
import tel.schich.convertedsync.Timing.time
import tel.schich.convertedsync.io._
import tel.schich.convertedsync.mime.TikaMimeDetector

object Synchronizer {

	val TempSuffix: String = ".temporary"

	case class ConvertibleFile(file: FileInfo, rule: ConversionRule)

	sealed trait ConversionResult
	case object Success extends ConversionResult
	case class Failure(sourceFile: FileInfo, reason: String) extends ConversionResult

	private def syncFromTo(conf: Config, local: IOAdapter, remote: IOAdapter): Boolean = {

		if (!remote.exists(conf.target)) {
			println("Target directory does not exist!")
			System.exit(1)
		}

		if (conf.threadCount > 0) {
			println(s"Using ${conf.threadCount} thread(s).")
			Seq("min", "num", "max")
				.map(p => s"scala.concurrent.context.${p}Threads")
				.foreach(System.setProperty(_: String, "" + conf.threadCount))
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
			val newTarget = f.file.reframeCore(target.base, target.extension)
			val newTargetParent = Util.parentPath(newTarget, remote.separator)
			if (!remote.exists(newTargetParent)) {
				remote.mkdirs(newTargetParent)
			}
			if (remote.rename(target.fullPath, newTarget)) {
				local.updatePreviousCore(f.file.fullPath, f.file.core)
			}
		}

		println(s"${filesToProcess.length} source files will be synchronized to the target folder.")

		val failures = filesToProcess.map(convert(conf, local, remote)).flatMap {
			case Success => Nil
			case f: Failure => Seq(f)
		}
		if (failures.nonEmpty) {
			println("Conversion failures:")
			for (fail <- failures) {
				println(s"\t${fail.sourceFile.fullPath}: ${fail.reason}")
			}
		}

		if (conf.purge) {
			println("Purge empty directories...")
			remote.purgeEmptyFolders(conf.target)
		}

		println("Done!")
		true
	}

	def convert(conf: Config, local: IOAdapter, remote: IOAdapter)(file: ConvertibleFile): ConversionResult = {

		val ConvertibleFile(f, rule) = file
		// the target path
		val target = conf.target.toString + local.separator + f.core + '.' + rule.extension
		// a temporary target path on the same file system as the target path
		val tmpTarget = target + TempSuffix
		// an intermediate target path on an arbitrary file system
		val intermediateTarget = conf.intermediateDir.fold(tmpTarget) { d =>
			val fileName = f.fileName
			val tempFile = Files.createTempFile(d, "intermediate_", s"_$fileName")
			if (Files.exists(tempFile)) {
				Files.delete(tempFile)
			}
			tempFile.toString
		}

		val intermediateAdapter =
			if (intermediateTarget.equals(tmpTarget)) remote
			else local

		val dir = Util.parentPath(target, remote.separator)
		if (!remote.exists(dir)) {
			remote.mkdirs(dir)
		}

		val relativeFreeSpace = remote.relativeFreeSpace(dir)
		println("Free space on target file system: %1.2f%%".format(relativeFreeSpace))
		if (relativeFreeSpace < conf.lowSpaceThreshold) Failure(f, s"The target file system ran out of disk space (free space below ${conf.lowSpaceThreshold}%)")
		else {
			val fullPath = Paths.get(f.fullPath)

			if (!Files.exists(fullPath)) Failure(f, "The file was queued for conversion, but disappeared!")
			else {

				val (success, t) = if (f.mime == rule.targetMime && !conf.force) {
					println("The input file mime type matches the target mime type, copying...")
					time() {
						intermediateAdapter.copy(f.fullPath, intermediateTarget)
					}
				} else {
					time() {
						runConverter(conf, f, intermediateTarget, rule)
						intermediateAdapter.exists(intermediateTarget)
					}
				}
				if (!success) Failure(f, s"Converter did not generate file $intermediateTarget")
				else {
					if (intermediateAdapter == local && !remote.move(intermediateTarget, tmpTarget)) {
						local.delete(intermediateTarget)
					}
					remote.rename(tmpTarget, target)
					local.updatePreviousCore(f.fullPath, f.core)
					println(s"Conversion completed after $t seconds: ${f.fullPath}\n"
						+ s"    Now at: $target")
					Success
				}
			}
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
