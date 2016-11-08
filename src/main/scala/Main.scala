import java.io.FileNotFoundException
import java.lang.ProcessBuilder.Redirect
import java.nio.file._
import java.nio.file.attribute.FileTime

import Main.FileDescription
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import play.api.libs.json.Json

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.Source

object Main extends App {

	object Config {
		implicit val format = Json.format[Config]
	}

	case class FileDescription(fullPath: Path, withoutExtension: Path, lastModified: FileTime, mime: String)

	case class Config(source: String, target: String, scriptDir: String, goal: String, extension: String)


	val conf = Json.parse(Source.fromFile("conf.json").mkString).as[Config]
	val sourceDir = Paths.get(conf.source)
	val targetDir = Paths.get(conf.target)
	val scriptDir = Paths.get(conf.scriptDir).toRealPath()

	val sourceFiles = DirectoryScanner.scanEnriched(sourceDir)
	val targetFiles = DirectoryScanner.scanEnriched(targetDir).map(x => (x.withoutExtension, x)).toMap


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
			if (f.mime == conf.goal) {
				Files.copy(f.fullPath, tmpTarget)
				tmpTarget
			} else {
				val script = scriptDir.resolve(f.mime)
				if (Files.isExecutable(script)) {
					val pb = new ProcessBuilder()
					pb.command(script.toString, f.fullPath.toString, tmpTarget.toString)
					pb.redirectInput(Redirect.INHERIT)
					pb.redirectOutput(Redirect.INHERIT)
					pb.redirectError(Redirect.INHERIT)
					val process = pb.start()
					process.waitFor() match {
						case 0 => tmpTarget
						case status => throw new Exception(s"The conversion script existed with $status: $script")
					}
				} else {
					throw new FileNotFoundException(s"Conversion script not found: $script")
				}
			}
		}.map { tmp =>
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
			target
		}
	}

	val result = Await.result(Future.sequence(futures), Duration.Inf)
	println("Done! Converted files:")
}

object DirectoryScanner {

	val tika = new TikaConfig()

	def scan(path: Path): Stream[Path] = {
		if (Files.isDirectory(path)) {
			Files.walk(path).iterator().toStream.filter(Files.isRegularFile(_))
		} else {
			Stream.empty
		}
	}

	def scanEnriched(path: Path): Stream[FileDescription] = {
		val files = scan(path)
		val metadata = new Metadata
		files.map { f =>
			val relative = path.relativize(f)
			val fileName = relative.getFileName.toString
			val strippedRelative = fileName.lastIndexOf('.') match {
				case -1 => relative
				case index => relative.getParent.resolve(fileName.substring(0, index))
			}

			val tikaStream = TikaInputStream.get(f)
			val mediaType = try {
				tika.getDetector.detect(tikaStream, metadata)
			} finally {
				tikaStream.close()
			}

			FileDescription(f, strippedRelative, Files.getLastModifiedTime(f), mediaType.toString)
		}
	}
}
