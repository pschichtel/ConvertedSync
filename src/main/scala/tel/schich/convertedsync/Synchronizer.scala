package tel.schich.convertedsync

import java.nio.file.*
import java.util.concurrent.TimeUnit.SECONDS

import org.apache.tika.config.TikaConfig
import tel.schich.convertedsync.ConversionRule.findRule
import tel.schich.convertedsync.Timing.time
import tel.schich.convertedsync.io.*
import tel.schich.convertedsync.mime.TikaMimeDetector

import scala.collection.parallel.ParSeq

object Synchronizer {

	private val TempSuffix: String = ".temporary"

	private def syncFromTo(conf: Config, local: IOAdapter, remote: IOAdapter): Boolean = {

		if (!remote.exists(conf.target)) {
			println("Target directory does not exist!")
			System.exit(1)
		}

		val threadCount =
			if (conf.threadCount > 0) conf.threadCount
			else Runtime.getRuntime.availableProcessors()
		println(s"Using $threadCount thread(s).")
		Seq("min", "num", "max")
			.map(p => s"scala.concurrent.context.${p}Threads")
			.foreach(System.setProperty(_: String, "" + threadCount))

		println(s"Scanning the target directory: ${conf.target} ...")
		val (targetFiles, targetScanTime) = time(SECONDS) {
			remote.files(conf.target)
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

		val (filesToProcess, filesToRename: ParSeq[ConvertibleFile]) =
			if (conf.reEncodeAll) (sourceFiles, ParSeq.empty)
			else {
				println("Detecting files to be processed ...")
				val (missingOrInvalid, stillValid) = sourceFiles.partition { f =>
					f.isInvalid || conf.enforceMime && f.mimeMismatched
				}

				println("Detecting files to be renamed ...")
				val rename = stillValid.filter { f =>
					f.isRenamed
				}
				(missingOrInvalid, rename)
			}

		if (conf.purge) {
			println("Purging obsolete files from the target directory ...")
			val targets = sourceFiles.flatMap(_.existingTarget).map(_.core).toSet
			val filesToPurge = targetFiles.filterNot(f => targets.contains(f.core))

			for (f <- filesToPurge) {
				remote.delete(f.fullPath)
				println(s"Purged ${f.fullPath} (${f.mime})")
			}

			println("Purge empty directories...")
			remote.purgeEmptyFolders(conf.target)
		}

		for {
			f <- filesToRename
			target <- f.existingTarget
		} {
			println(s"Renaming: ${target.fullPath}")
			val newTarget = f.sourceFile.reframeCore(remote, target.base, target.extension)
			val newTargetParent = Util.parentPath(newTarget, remote.separator)
			if (!remote.exists(newTargetParent)) {
				remote.mkdirs(newTargetParent)
			}
			if (remote.rename(target.fullPath, newTarget)) {
				println(s"\tNow at: $newTarget")
			} else {
				println("\tRename failed!")
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
				println(s"\t${displayIndex(i, failures.length)} ${fail.sourceFile.fullPath}: ${fail.reason}")
			}
		}

		val coresToUpdate = sourceFiles.flatMap { case ConvertibleFile(f, _, _) =>
			val coreUpdate = Some((f.fullPath, f.core))
			f.previousCore match {
				case Some(previousCore) if f.core != previousCore => coreUpdate
				case None => coreUpdate
				case _ => None
			}
		}

		if (coresToUpdate.nonEmpty) {
			println("Storing new location in source files...")
			coresToUpdate.foreach(local.updatePreviousCore.tupled)
		}

		println("Done!")
		failures.isEmpty
	}

	private def displayIndex(i: Int, len: Int): String =
		s"${(i + 1).toString.reverse.padTo(len.toString.length, ' ').reverse}."

	def convert(conf: Config, local: IOAdapter, remote: IOAdapter)(file: ConvertibleFile): ConversionResult = {

		val ConvertibleFile(f, rule, existing) = file
		// rebase source-core onto the target base with given extension
		val rebase = f.reframeCore(remote, conf.target, _: String)
		// the expected target path given the source file and the conversion rule
		val expectedTarget = rebase(s".${rule.extension}")
		// the target either based on the already existing file or on the expected target
		val target = existing.fold(expectedTarget)(_.fullPath)
		// the directory the target file will be placed in
		val targetDirectory = Util.parentPath(target, remote.separator)
		// a temporary target path on the same file system as the target path
		val tmpTarget = rebase(TempSuffix)
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

		if (!local.exists(f.fullPath)) Failure(f, "The file was queued for conversion, but disappeared!")
		else {
			intermediateAdapter.mkdirs(Util.parentPath(intermediateTarget, intermediateAdapter.separator))
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

			result flatMap {
				if (!remote.exists(targetDirectory)) {
					if (remote.mkdirs(targetDirectory)) Success
					else Failure(f, "Failed to create the target directory")
				} else Success
			} flatMap {

				val relativeFreeSpace = remote.relativeFreeSpace(targetDirectory) match {
					case Left(error) =>
						println(s"Failed to detect free space on target: $error")
						Double.MaxValue
					case Right(freeSpace) =>
						println("Free space on target file system: %1.1f%%".format(freeSpace * 100))
						freeSpace
				}

				if (relativeFreeSpace < conf.lowSpaceThreshold) Failure(f, s"The target file system ran out of disk space (free space below ${conf.lowSpaceThreshold}%)")
				else Success

			} flatMap {
				if (target == expectedTarget) {
					if (remote.rename(tmpTarget, target)) Success
					else Failure(f, "Failed to rename the file to the final name!")
				} else {
					if (remote.delete(target)) {
						if (remote.rename(tmpTarget, expectedTarget)) Success
						else Failure(f, "Failed to move over the new version!")
					} else Failure(f, "Failed to delete the existing file!")
				}
			} flatMap {
				println(s"Conversion completed after ${t}ms: ${f.fullPath}\n"
					+ s"\tNow at: $target")
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
				val status = script.invoke(Vector(sourceFile.fullPath, targetFile))
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
						Some(new ShellAdapter(mime, script))
					case _ => None
				}
			case None => Some(localAdapter)
		}
	}
}
