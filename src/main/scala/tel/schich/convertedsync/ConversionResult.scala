package tel.schich.convertedsync

import tel.schich.convertedsync.io.FileInfo

sealed trait ConversionResult {
	def isSuccess: Boolean
	def flatMap(f: => ConversionResult): ConversionResult
}
case object Success extends ConversionResult {
	override val isSuccess: Boolean = true

	override def flatMap(f: => ConversionResult): ConversionResult = f
}
case class Failure(sourceFile: FileInfo, reason: String) extends ConversionResult with Ordered[Failure] {
	override val isSuccess: Boolean = false

	override def flatMap(f: => ConversionResult): ConversionResult = this

	override def compare(that: Failure): Int = this.sourceFile.compare(that.sourceFile)
}
