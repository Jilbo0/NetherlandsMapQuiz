package backend

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoScoring {

	private const val EARTH_RADIUS_KM = 6371.0

	/** Great-circle distance between two lat/lng points, in kilometers. */
	fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
		val dLat = Math.toRadians(lat2 - lat1)
		val dLng = Math.toRadians(lng2 - lng1)
		val a = sin(dLat / 2).pow(2) +
				cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
		val c = 2 * atan2(sqrt(a), sqrt(1 - a))
		return EARTH_RADIUS_KM * c
	}

	/**
	 * Seterra-style scoring: full points within a small radius, decaying smoothly
	 * to zero by ~maxDistanceKm. The Netherlands is small (~300km across), so the
	 * decay curve is tuned tighter than a world-map game would use.
	 *
	 * - 0 to 3km: 5000 points (essentially a perfect click)
	 * - decays smoothly after that
	 * - 0 points at/after 150km (roughly the country's diagonal)
	 */
	fun scoreForDistance(distanceKm: Double, maxDistanceKm: Double = 150.0): Int {
		if (distanceKm <= 3.0) return 5000
		if (distanceKm >= maxDistanceKm) return 0
		val t = (distanceKm - 3.0) / (maxDistanceKm - 3.0) // 0..1
		// Smooth ease-out so points fall fast near the center, gently near the edge.
		val factor = (1 - t).pow(2)
		return (factor * 5000).toInt().coerceIn(0, 5000)
	}
}

object NameMatching {

	/**
	 * Normalizes a place name for comparison: lowercases, strips a parenthetical
	 * disambiguator (e.g. "Aalst (Buren)" -> "aalst"), and trims diacritics-insensitive
	 * punctuation differences.
	 */
	fun normalize(name: String): String {
		return name.lowercase()
			.replace(Regex("""\(.*?\)"""), "")
			.replace(Regex("""[^a-z0-9]+"""), " ")
			.trim()
	}

	/** Standard Levenshtein edit distance between two strings. */
	fun editDistance(a: String, b: String): Int {
		val dp = Array(a.length + 1) { IntArray(b.length + 1) }
		for (i in 0..a.length) dp[i][0] = i
		for (j in 0..b.length) dp[0][j] = j
		for (i in 1..a.length) {
			for (j in 1..b.length) {
				dp[i][j] = if (a[i - 1] == b[j - 1]) {
					dp[i - 1][j - 1]
				} else {
					1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
				}
			}
		}
		return dp[a.length][b.length]
	}

	/**
	 * Checks a guess against the correct name. Returns exact match, "close" (small
	 * typo allowance scaled to name length), or wrong, plus the edit distance.
	 */
	fun checkGuess(guess: String, correct: String): Triple<Boolean, Boolean, Int> {
		val normGuess = normalize(guess)
		val normCorrect = normalize(correct)
		if (normGuess == normCorrect) return Triple(true, true, 0)

		val distance = editDistance(normGuess, normCorrect)
		// Allow proportionally more typos for longer names (e.g. 1 typo per ~5 chars, min 1).
		val allowance = maxOf(1, normCorrect.length / 5)
		val isClose = distance <= allowance
		return Triple(false, isClose, distance)
	}
}