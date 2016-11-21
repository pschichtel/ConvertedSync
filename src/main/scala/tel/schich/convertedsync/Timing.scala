package tel.schich.convertedsync

import java.util.concurrent.TimeUnit

object Timing {
	def time[U](timeUnit: TimeUnit = TimeUnit.MILLISECONDS)(f: => U): (U, Long) = {
		val startTime = System.currentTimeMillis()
		val r = f
		(r, timeUnit.convert(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS))
	}
}
