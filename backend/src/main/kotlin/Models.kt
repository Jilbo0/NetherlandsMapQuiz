package backend

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** A single geocoded place, as loaded from places.json (output of the geocoder module). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Place(
	val id: String,
	val name: String,
	val province: String?,
	val lat: Double,
	val lng: Double,
	val displayName: String,
	val confidence: String
)

/** Public-facing place info — no coordinates, so the client can't cheat by reading the JSON. */
data class PlaceSummary(
	val id: String,
	val name: String,
	val province: String?
)

fun Place.toSummary() = PlaceSummary(id, name, province)

/** Request body for the click-on-map guess mode. */
data class ClickGuessRequest(
	val placeId: String,
	val guessLat: Double,
	val guessLng: Double
)

data class ClickGuessResponse(
	val placeId: String,
	val correctName: String,
	val distanceKm: Double,
	val score: Int,
	val correctLat: Double,
	val correctLng: Double,
	val clickedProvince: String?
)

/** Request body for the type-the-name guess mode. */
data class NameGuessRequest(
	val placeId: String,
	val guessName: String
)

data class NameGuessResponse(
	val placeId: String,
	val correctName: String,
	val isCorrect: Boolean,
	val isClose: Boolean, // small typo / minor difference
	val distanceEdits: Int
)

/** Request body for the province-guess mode (click a province, given a place name). */
data class ProvinceGuessRequest(
	val placeId: String,
	val guessedProvince: String
)

data class ProvinceGuessResponse(
	val placeId: String,
	val correctProvince: String,
	val isCorrect: Boolean
)

/** A single round to play: the place to find, in whichever mode the client is running. */
data class RoundResponse(
	val placeId: String,
	val name: String?, // present for "click" mode (name shown, find it on map)
	val province: String?
)