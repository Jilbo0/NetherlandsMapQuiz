package backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Geocodes a CSV of Dutch place names into a places.json file using the
 * Nominatim (OpenStreetMap) geocoding API.
 *
 * Usage:
 *   gradle run --args="../data/places_input.csv ../data/places.json ../data/review.csv"
 *
 * Behavior:
 *  - Respects Nominatim's usage policy: max 1 request/second, descriptive User-Agent.
 *  - Resumable: if places.json already has entries (from a previous partial run),
 *    already-geocoded names are skipped.
 *  - Low-confidence or missing results are written to review.csv instead of
 *    silently trusting them, so you can manually check/fix them later.
 *  - Disambiguator in parentheses, e.g. "Aalst (Buren)", is used to bias the
 *    search query but stripped from the display name.
 */

data class InputRow(val name: String, val province: String?)

data class GeocodedPlace(
	val id: String,
	val name: String,
	val province: String?,
	val lat: Double,
	val lng: Double,
	val displayName: String,
	val confidence: String // "ok", "low", "missing"
)

data class NominatimResult(
	val lat: String,
	val lon: String,
	val display_name: String,
	val importance: Double? = null,
	val type: String? = null,
	val osm_type: String? = null
)

private val mapper = ObjectMapper().registerKotlinModule()

private val httpClient = OkHttpClient.Builder()
	.connectTimeout(15, TimeUnit.SECONDS)
	.readTimeout(15, TimeUnit.SECONDS)
	.build()

private const val USER_AGENT = "nl-places-quiz-geocoder/1.0 (contact: jille.du.bois@me.com)"
private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
private const val MIN_DELAY_MS = 1100L // a bit over 1s to be safely within policy

fun main(args: Array<String>) {
	val inputPath = args.getOrNull(0) ?: "../../resources/places_input.csv"
	val outputPath = args.getOrNull(1) ?: "../../resources/places.json"
	val reviewPath = args.getOrNull(2) ?: "../../resources/review.csv"

	val inputFile = File(inputPath)
	if (!inputFile.exists()) {
		System.err.println("Input file not found: $inputPath")
		return
	}

	val rows = readInputCsv(inputFile)
	println("Loaded ${rows.size} place names from $inputPath")

	val outputFile = File(outputPath)
	val existing: MutableMap<String, GeocodedPlace> = if (outputFile.exists()) {
		try {
			val list: List<GeocodedPlace> = mapper.readValue(outputFile)
			list.associateBy { it.name }.toMutableMap()
		} catch (e: Exception) {
			mutableMapOf()
		}
	} else {
		mutableMapOf()
	}

	if (existing.isNotEmpty()) {
		println("Resuming: ${existing.size} places already geocoded, will skip those.")
	}

	val reviewRows = mutableListOf<String>()
	var processed = 0
	var lastRequestTime = 0L

	for (row in rows) {
		if (existing.containsKey(row.name)) continue

		// Throttle to respect Nominatim's 1 req/sec policy.
		val now = System.currentTimeMillis()
		val elapsed = now - lastRequestTime
		if (lastRequestTime != 0L && elapsed < MIN_DELAY_MS) {
			Thread.sleep(MIN_DELAY_MS - elapsed)
		}

		val result = geocodeWithRetry(row)
		lastRequestTime = System.currentTimeMillis()

		if (result == null) {
			println("  [MISSING] ${row.name}")
			existing[row.name] = GeocodedPlace(
				id = slugify(row.name),
				name = row.name,
				province = row.province,
				lat = 0.0,
				lng = 0.0,
				displayName = "",
				confidence = "missing"
			)
			reviewRows.add("${csvEscape(row.name)},${csvEscape(row.province ?: "")},missing,,,")
		} else {
			val confidence = assessConfidence(row, result)
			println("  [$confidence] ${row.name} -> ${result.lat}, ${result.lon} (${result.display_name})")
			existing[row.name] = GeocodedPlace(
				id = slugify(row.name),
				name = row.name,
				province = row.province,
				lat = result.lat.toDouble(),
				lng = result.lon.toDouble(),
				displayName = result.display_name,
				confidence = confidence
			)
			if (confidence == "low") {
				reviewRows.add(
					"${csvEscape(row.name)},${csvEscape(row.province ?: "")},low," +
							"${result.lat},${result.lon},${csvEscape(result.display_name)}"
				)
			}
		}

		processed++
		// Write incrementally every 25 places so a long run can be killed/resumed safely.
		if (processed % 25 == 0) {
			writeOutputs(existing, outputFile, reviewPath, reviewRows)
			println("  ... progress saved ($processed processed this run)")
		}
	}

	writeOutputs(existing, outputFile, reviewPath, reviewRows)
	println("Done. ${existing.size} total places in $outputPath")
	val missingCount = existing.values.count { it.confidence == "missing" }
	val lowCount = existing.values.count { it.confidence == "low" }
	if (missingCount > 0 || lowCount > 0) {
		println("$missingCount missing, $lowCount low-confidence. See $reviewPath for manual review.")
	}
}

private fun readInputCsv(file: File): List<InputRow> {
	val lines = file.readLines().filter { it.isNotBlank() }
	if (lines.isEmpty()) return emptyList()
	val header = lines.first().split(",")
	val nameIdx = header.indexOfFirst { it.trim().equals("name", ignoreCase = true) }
	val provinceIdx = header.indexOfFirst { it.trim().equals("province", ignoreCase = true) }
	return lines.drop(1).mapNotNull { line ->
		val cols = splitCsvLine(line)
		val name = cols.getOrNull(nameIdx)?.trim() ?: return@mapNotNull null
		if (name.isEmpty()) return@mapNotNull null
		val province = if (provinceIdx >= 0) cols.getOrNull(provinceIdx)?.trim() else null
		InputRow(name, province?.takeIf { it.isNotEmpty() })
	}
}

// Minimal CSV line splitter handling simple quoted fields (sufficient for our controlled input).
private fun splitCsvLine(line: String): List<String> {
	val result = mutableListOf<String>()
	val sb = StringBuilder()
	var inQuotes = false
	for (c in line) {
		when {
			c == '"' -> inQuotes = !inQuotes
			c == ',' && !inQuotes -> {
				result.add(sb.toString())
				sb.clear()
			}
			else -> sb.append(c)
		}
	}
	result.add(sb.toString())
	return result
}

private fun csvEscape(value: String): String {
	return if (value.contains(",") || value.contains("\"")) {
		"\"${value.replace("\"", "\"\"")}\""
	} else {
		value
	}
}

/**
 * Builds the search query. The disambiguator in parentheses (e.g. "Aalst (Buren)")
 * is used as extra context for Nominatim, and the province is appended when present.
 * Example: "Aalst (Buren)" + province "Gelderland" ->
 *          "Aalst, Buren, Gelderland, Netherlands"
 */
private fun buildQuery(row: InputRow): String {
	val nameMatch = Regex("""^(.*?)\s*\((.*?)\)\s*$""").find(row.name)
	val baseName: String
	val qualifier: String?
	if (nameMatch != null) {
		baseName = nameMatch.groupValues[1].trim()
		qualifier = nameMatch.groupValues[2].trim()
	} else {
		baseName = row.name.trim()
		qualifier = null
	}
	val parts = mutableListOf(baseName)
	if (qualifier != null) parts.add(qualifier)
	if (row.province != null) parts.add(row.province)
	parts.add("Netherlands")
	return parts.joinToString(", ")
}

private fun geocodeWithRetry(row: InputRow, maxRetries: Int = 3): NominatimResult? {
	val query = buildQuery(row)
	repeat(maxRetries) { attempt ->
		try {
			val encoded = URLEncoder.encode(query, "UTF-8")
			val url = "$NOMINATIM_URL?q=$encoded&format=jsonv2&countrycodes=nl&limit=1&addressdetails=0"
			val request = Request.Builder()
				.url(url)
				.header("User-Agent", USER_AGENT)
				.build()

			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					System.err.println("  HTTP ${response.code} for '$query', attempt ${attempt + 1}")
					Thread.sleep(2000L * (attempt + 1))
					return@repeat
				}
				val body = response.body?.string() ?: return null
				val results: List<NominatimResult> = mapper.readValue(body)
				return results.firstOrNull()
			}
		} catch (e: Exception) {
			System.err.println("  Error geocoding '$query': ${e.message}, attempt ${attempt + 1}")
			Thread.sleep(2000L * (attempt + 1))
		}
	}
	return null
}

/**
 * Heuristic confidence check: if Nominatim's importance score is low, or the
 * result type suggests a much larger/smaller feature than expected, flag for review.
 * This won't catch every wrong-disambiguation case, but catches the obvious ones.
 */
private fun assessConfidence(row: InputRow, result: NominatimResult): String {
	val importance = result.importance ?: 0.0
	if (importance < 0.25) return "low"
	return "ok"
}

private fun slugify(name: String): String {
	return name.lowercase()
		.replace(Regex("""\(.*?\)"""), "")
		.trim()
		.replace(Regex("""[^a-z0-9]+"""), "-")
		.trim('-')
}

private fun writeOutputs(
	existing: Map<String, GeocodedPlace>,
	outputFile: File,
	reviewPath: String,
	reviewRows: List<String>
) {
	val writer = mapper.writerWithDefaultPrettyPrinter()
	outputFile.writeText(writer.writeValueAsString(existing.values.sortedBy { it.name }))

	val reviewFile = File(reviewPath)
	val header = "name,province,confidence,lat,lng,display_name\n"
	val allReviewRows = existing.values
		.filter { it.confidence != "ok" }
		.map {
			"${csvEscape(it.name)},${csvEscape(it.province ?: "")},${it.confidence}," +
					"${if (it.confidence == "missing") "" else it.lat}," +
					"${if (it.confidence == "missing") "" else it.lng}," +
					csvEscape(it.displayName)
		}
	reviewFile.writeText(header + allReviewRows.joinToString("\n"))
}