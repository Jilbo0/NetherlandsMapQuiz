package backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

class PlaceRepository(private val dataFile: File) {

	private val mapper = ObjectMapper().registerKotlinModule()
	private var places: List<Place> = emptyList()
	private var placesById: Map<String, Place> = emptyMap()

	fun load() {
		if (!dataFile.exists()) {
			throw IllegalStateException(
				"places.json not found at ${dataFile.absolutePath}. " +
						"Run the geocoder module first to generate it."
			)
		}
		val loaded: List<Place> = mapper.readValue(dataFile.readText())
		// Only serve places that actually geocoded successfully — "missing" entries
		// (lat=0,lng=0 placeholders) would otherwise corrupt distance scoring.
		places = loaded.filter { it.confidence != "missing" }
		placesById = places.associateBy { it.id }
		println("Loaded ${places.size} usable places (${loaded.size - places.size} skipped as missing).")
	}

	fun all(): List<Place> = places

	fun byId(id: String): Place? = placesById[id]

	fun randomPlace(excludeId: String? = null): Place? {
		if (places.isEmpty()) return null
		if (places.size == 1) return places.first()
		var candidate = places.random()
		var attempts = 0
		while (candidate.id == excludeId && attempts < 10) {
			candidate = places.random()
			attempts++
		}
		return candidate
	}
}