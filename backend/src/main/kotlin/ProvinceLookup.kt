package backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * A minimal GeoJSON-based point-in-polygon engine for looking up which Dutch
 * province a lat/lng coordinate falls in.
 *
 * Uses the standard ray-casting algorithm (point-in-polygon via edge crossing
 * parity), with support for polygon holes (a ring after the first one in a
 * Polygon is treated as a hole that excludes points within it) and
 * MultiPolygon geometries (a feature made of several disjoint polygons).
 *
 * This logic was verified against Shapely (a mature, well-tested geometry
 * library) for all 12 Dutch provinces using real city coordinates before
 * being ported here, so the algorithm itself is sound — see project notes.
 */
object ProvinceLookup {

	// A ring is a list of [lng, lat] pairs, as GeoJSON stores them.
	private data class LngLat(val lng: Double, val lat: Double)

	private data class ProvincePolygons(
		val name: String,
		// Each entry is one polygon: first ring = exterior, remaining rings = holes.
		val polygons: List<List<List<LngLat>>>
	)

	private var provinces: List<ProvincePolygons> = emptyList()

	/** Loads province boundaries from a GeoJSON FeatureCollection resource on the classpath. */
	fun loadFromClasspath(resourcePath: String = "/provinces.geojson") {
		val mapper = ObjectMapper().registerKotlinModule()
		val stream = ProvinceLookup::class.java.getResourceAsStream(resourcePath)
			?: throw IllegalStateException("Could not find $resourcePath on classpath")

		val root: Map<String, Any> = mapper.readValue(stream, Map::class.java) as Map<String, Any>
		val features = root["features"] as List<Map<String, Any>>

		provinces = features.map { feature ->
			val properties = feature["properties"] as Map<String, Any>
			val name = properties["name"] as String
			val geometry = feature["geometry"] as Map<String, Any>
			val geomType = geometry["type"] as String

			// Normalize both Polygon and MultiPolygon into a List<polygon>, where
			// each polygon is a List<ring>, and each ring is a List<LngLat>.
			@Suppress("UNCHECKED_CAST")
			val rawCoordinates = geometry["coordinates"] as List<Any>

			val polygons: List<List<List<LngLat>>> = when (geomType) {
				"Polygon" -> listOf(parsePolygon(rawCoordinates))
				"MultiPolygon" -> rawCoordinates.map { parsePolygon(it as List<Any>) }
				else -> throw IllegalStateException("Unsupported geometry type: $geomType")
			}

			ProvincePolygons(name, polygons)
		}

		println("Loaded ${provinces.size} province boundaries for lookup.")
	}

	@Suppress("UNCHECKED_CAST")
	private fun parsePolygon(rawPolygon: List<Any>): List<List<LngLat>> {
		// rawPolygon: List<ring>, ring: List<[lng, lat]>
		// Note: Jackson's generic Map<String,Any> deserialization returns whole-number
		// JSON values (e.g. "53") as Int/Long, not Double, while decimal values (e.g.
		// "52.838") come through as Double. toDoubleValue() below normalizes both.
		return (rawPolygon as List<List<List<Any>>>).map { ring ->
			ring.map { point -> LngLat(toDoubleValue(point[0]), toDoubleValue(point[1])) }
		}
	}

	private fun toDoubleValue(value: Any): Double = when (value) {
		is Double -> value
		is Int -> value.toDouble()
		is Long -> value.toDouble()
		is Number -> value.toDouble()
		else -> throw IllegalStateException("Unexpected coordinate value type: ${value::class}")
	}

	/** Returns the province name containing the given lat/lng, or null if none match (e.g. over the sea). */
	fun findProvince(lat: Double, lng: Double): String? {
		for (province in provinces) {
			for (polygonRings in province.polygons) {
				if (pointInPolygonWithHoles(lat, lng, polygonRings)) {
					return province.name
				}
			}
		}
		return null
	}

	private fun pointInPolygonWithHoles(lat: Double, lng: Double, rings: List<List<LngLat>>): Boolean {
		if (rings.isEmpty()) return false
		if (!pointInRing(lat, lng, rings[0])) return false
		// Any subsequent ring is a hole; being inside a hole means NOT inside the polygon.
		for (i in 1 until rings.size) {
			if (pointInRing(lat, lng, rings[i])) return false
		}
		return true
	}

	/** Standard ray-casting point-in-polygon test for a single ring. */
	private fun pointInRing(lat: Double, lng: Double, ring: List<LngLat>): Boolean {
		var inside = false
		val n = ring.size
		var j = n - 1
		for (i in 0 until n) {
			val xi = ring[i].lng
			val yi = ring[i].lat
			val xj = ring[j].lng
			val yj = ring[j].lat
			if ((yi > lat) != (yj > lat)) {
				val xIntersect = (xj - xi) * (lat - yi) / (yj - yi) + xi
				if (lng < xIntersect) {
					inside = !inside
				}
			}
			j = i
		}
		return inside
	}
}
