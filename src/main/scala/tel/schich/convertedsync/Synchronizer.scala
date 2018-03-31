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

		println(s"Scanning the target directory: ${conf.target} ...")
		val (targetFiles, targetScanTime) = time(SECONDS) {
			remote.files(conf.target.toString)
		}
		println(s"Found ${targetFiles.size} files in the target directory in $targetScanTime seconds.")

		println(s"Scanning the source directory: ${conf.source} ...")
		val (sourceFiles, sourceScanTime) = time(SECONDS) {
			val targetLookup = targetFiles.map(x => (x.core, x)).toMap

			local.files(conf.source.toString).flatMap { f =>
				findRule(f.mime, conf.rules) match {
					case Some(rule) =>
						val existingFile = targetLookup.get(f.core).orElse(f.previousCore.flatMap(targetLookup.get))
						Some(ConvertibleFile(f, rule, existingFile))
					case None =>
						println(s"No applicable conversion rule for file: ${f.fullPath} (${f.mime})")
						None
				}
			}
		}
		println(s"Found ${sourceFiles.length} source files in $sourceScanTime seconds.")

		println("File type distribution:")
		sourceFiles.groupBy(cf => cf.sourceFile.mime).foreach {
			case (group, files) => println(s"\t$group -> ${files.length}")
		}

		val (filesToProcess, filesToRename, validFiles) = if (conf.reEncodeAll) (sourceFiles, Nil, Nil)
		else {
			println("Detecting files to be processed ...")
			val (process, dontProcess) = sourceFiles.partition { f =>
				f.isInvalid || conf.enforceMime && f.mimeMismatched
			}

			println("Detecting files to be renamed ...")
			val (rename, valid) = dontProcess.partition { f =>
				f.isRenamed
			}
			(process, rename, valid)
		}

		if (conf.purge) {
			println("Purging obsolete files from the target directory ...")
			val handledFiles = (validFiles ++ filesToProcess).map(_.sourceFile.core).toSet ++ filesToRename.flatMap(_.sourceFile.previousCore)
			val filesToPurge = targetFiles.filterNot(f => handledFiles.contains(f.core))

			for (f <- filesToPurge) {
				remote.delete(f.fullPath)
				println(s"Purged ${f.fullPath} (${f.mime})")
			}
		}

		for {
			f <- filesToRename
			target <- f.existingTarget
		} {
			val newTarget = f.sourceFile.reframeCore(target.base, target.extension)
			val newTargetParent = Util.parentPath(newTarget, remote.separator)
			if (!remote.exists(newTargetParent)) {
				remote.mkdirs(newTargetParent)
			}
			if (remote.rename(target.fullPath, newTarget)) {
				local.updatePreviousCore(f.sourceFile.fullPath, f.sourceFile.core)
			}
		}

		println(s"${filesToProcess.length} source files will be synchronized to the target folder.")

		val failures = filesToProcess.map(convert(conf, local, remote)).flatMap {
			case Success => None
			case f: Failure => Some(f)
		}
		if (failures.nonEmpty) {
			println("Conversion failures:")
			for ((fail, i) <- failures.seq.sorted.zipWithIndex) {
				println(s"\t${displayIndex(i, failures.length)}. ${fail.sourceFile.fullPath}: ${fail.reason}")
			}
		}

		if (conf.purge) {
			println("Purge empty directories...")
			remote.purgeEmptyFolders(conf.target)
		}

		println("Done!")
		failures.nonEmpty
	}

	def displayIndex(i: Int, len: Int): String =
		s"${(i + 1).toString.reverse.padTo(len.toString.length, ' ').reverse}."

	def convert(conf: Config, local: IOAdapter, remote: IOAdapter)(file: ConvertibleFile): ConversionResult = {

		val ConvertibleFile(f, rule, existing) = file
		// rebase source-core onto the target base
		val rebasedCore = conf.target + local.separator + f.core
		// the expected target path given the source file and the conversion rule
		val expectedTarget = rebasedCore + '.' + rule.extension
		// the target either based on the already existing file or on the expected target
		val target = existing.fold(expectedTarget)(_.fullPath)
		// a temporary target path on the same file system as the target path
		val tmpTarget = rebasedCore + TempSuffix
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

		val fullPath = Paths.get(f.fullPath)

		if (!Files.exists(fullPath)) Failure(f, "The file was queued for conversion, but disappeared!")
		else {

			val (result, t) = if (f.mime == rule.targetMime && !conf.force) {
				println("The input file mime type matches the target mime type, copying...")
				time() {
					if (intermediateAdapter.copy(f.fullPath, intermediateTarget)) Success
					else Failure(f, "Failed to copy the file over to the target!")
				}
			} else {
				time() {
					runConverter(conf, f, intermediateTarget, rule).flatMap {
						if (intermediateAdapter.exists(intermediateTarget)) Success
						else Failure(f, s"Converter did not generate file $intermediateTarget")
					}
				}
			}

			if (intermediateAdapter == local && !remote.move(intermediateTarget, tmpTarget)) {
				local.delete(intermediateTarget)
			}
			val dir = Util.parentPath(target, remote.separator)

			result flatMap {
				if (!remote.exists(dir)) {
					if (remote.mkdirs(dir)) Success
					else Failure(f, "Failed to create the target directory")
				} else Success
			} flatMap {

				val relativeFreeSpace = remote.relativeFreeSpace(dir) match {
					case Left(error) =>
						println(s"Failed to detect free space on target: $error")
						Double.MaxValue
					case Right(freeSpace) =>
						println("Free space on target file system: %1.2f%%".format(freeSpace))
						freeSpace
				}

				if (relativeFreeSpace < conf.lowSpaceThreshold) Failure(f, s"The target file system ran out of disk space (free space below ${conf.lowSpaceThreshold}%)")
				else Success

			} flatMap {
				if (target == expectedTarget) {
					if (remote.rename(tmpTarget, target)) Success
					else Failure(f, "Failed to rename the file to the final name!")
				} else {
					if (remote.delete(target) && remote.rename(tmpTarget, expectedTarget)) Success
					else Failure(f, "Failed to delete the existing file and move over the new version!")
				}
			} flatMap {
				local.updatePreviousCore(f.fullPath, f.core)
				println(s"Conversion completed after $t seconds: ${f.fullPath}\n"
					+ s"    Now at: $target")
				Success
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

	private def runConverter(conf: Config, sourceFile: FileInfo, targetFile: String, rule: ConversionRule): ConversionResult = {

		ShellScript.resolve(conf.convertersDir.resolve(rule.converter), !conf.silenceConverter) match {
			case Some(script) =>
				println(s"Applying converter: ${script.executable}")
				val status = script.invoke(Array(sourceFile.fullPath, targetFile))
				if (status != 0) {
					Failure(sourceFile, s"Converter for ${sourceFile.mime} was not successful: $status")
				} else Success
			case _ =>
				Failure(sourceFile, s"Converter for ${sourceFile.mime} not found!")
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
