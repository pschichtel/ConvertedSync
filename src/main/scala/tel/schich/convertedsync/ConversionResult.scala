package tel.schich.convertedsync

import tel.schich.convertedsync.io.FileInfo

sealed trait ConversionResult
case object Success extends ConversionResult
case class Failure(sourceFile: FileInfo, reason: String) extends ConversionResult
