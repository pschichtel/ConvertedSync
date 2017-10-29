package tel.schich.convertedsync

case class ConversionRule(sourceMime: String, targetMime: String, converter: String, extension: String) {
	def matches(mime: String): Boolean = ConversionRule.matchMime(mime, sourceMime)
}

object ConversionRule {
	def parse(rule: String): Option[ConversionRule] = {
		rule.split(':') match {
			case Array(sourceMime, targetMime, converter, extension) =>
				Some(ConversionRule(sourceMime, targetMime, converter, extension))
			case _ =>
				None
		}
	}

	def matchMime(actualMime: String, rule: String): Boolean = {
		val mimeParts = actualMime.split('/')
		val ruleParts = rule.split('/')

		if (ruleParts.length > mimeParts.length) false
		else {
			mimeParts.take(ruleParts.length).zip(ruleParts).exists {
				case (mimePart, rulePart) => mimePart.equalsIgnoreCase(rulePart)
			}
		}
	}

	def findRule(sourceMime: String, rules: Seq[ConversionRule]): Option[ConversionRule] =
		rules.find(_.matches(sourceMime))
}
