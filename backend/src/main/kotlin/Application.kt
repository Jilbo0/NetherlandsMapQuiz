package backend

import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.http.content.staticFiles
import java.io.File
import kotlin.math.roundToInt

/**
 * Backend for the Netherlands places quiz.
 *
 * Endpoints:
 *  GET  /places                 -> list of all places (id, name, province) — NO coordinates
 *  GET  /round/click            -> a random place's id+province only (client shows nothing yet;
 *                                   client will show the NAME and ask user to click the map)
 *  GET  /round/name?placeId=X   -> nothing extra needed; client already knows the place's id from
 *                                   /places and shows it on the map via a separate reveal step.
 *                                   (See README in this file's comments for mode flow.)
 *  GET  /round/province-target      -> a random place's id+name only — province is withheld, the
 *                                       user must click the province on the map.
 *  POST /guess/click            -> { placeId, guessLat, guessLng } -> distance + score
 *  POST /guess/name             -> { placeId, guessName } -> correct/close + edit distance
 *  POST /guess/province             -> { placeId, guessedProvince } -> correct/wrong + the
 *                                       correct province name (used for the reveal after 3 misses)
 *
 * Mode flow (kept deliberately simple, logic lives client-side for which mode is active):
 *  - "Click" mode: client picks a random place from /places, shows its NAME, user clicks the
 *    map, client POSTs the click to /guess/click which returns the real coordinates + score.
 *  - "Name" mode: client picks a random place from /places, shows its LOCATION (a marker on
 *    the map — backend must supply this only when starting a name round, see /round/name-target),
 *    user types a guess, client POSTs to /guess/name.
 *  - "Province" mode: client fetches /round/province-target (place name shown, province withheld),
 *    user clicks a province polygon on the map, client POSTs the clicked province's name to
 *    /guess/province. Wrong guesses can be retried (client tracks the attempt count and which
 *    provinces were already tried); after 3 wrong tries the response's correctProvince is used
 *    to reveal the answer client-side.
 *
 * Note: for "name" mode the client needs the coordinates to *display* the marker, so we expose
 * a dedicated endpoint that returns coordinates for a specific round (not the full list), to
 * avoid leaking the entire answer key up front the way GET /places does. The same principle
 * applies to "province" mode's /round/province-target endpoint.
 */

fun main() {
	val dataFile = File(System.getenv("PLACES_JSON_PATH") ?: "../data/places.json")
	val repository = PlaceRepository(dataFile)

	try {
		repository.load()
	} catch (e: Exception) {
		println("Error loading data: ${e.message}")
		return
	}
	ProvinceLookup.loadFromClasspath()

	embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
		module(repository)
		routing {
			staticFiles("/", File("frontend/src/main")) {
				default("index.html")
			}
		}
	}.start(wait = true)
}

object ProvinceNames {
	/**
	 * Maps common alternate spellings/names for Dutch provinces to the canonical
	 * name used by the boundary GeoJSON (which is what the frontend's clicked
	 * polygon name will always be). This lets place data use whichever spelling
	 * is most natural (e.g. "Friesland", the everyday Dutch name) without it
	 * needing to match the boundary data's official/statutory name exactly.
	 *
	 * Keys are lowercased for case-insensitive lookup; add more variants here
	 * if other places in your data use different province name spellings.
	 */
	private val aliases: Map<String, String> = mapOf(
		"friesland" to "Fryslân",
		"fryslan" to "Fryslân" // in case of missing/different diacritic encoding
	)

	/** Returns the canonical boundary-data province name for a given input name. */
	fun canonicalize(name: String): String {
		val trimmed = name.trim()
		return aliases[trimmed.lowercase()] ?: trimmed
	}
}

fun Application.module(repository: PlaceRepository) {
	install(ContentNegotiation) {
		jackson { }
	}
	install(CallLogging)
	install(CORS) {
		anyHost() // fine for local/personal use; tighten if you deploy this publicly
		allowHeader(HttpHeaders.ContentType)
		allowMethod(HttpMethod.Get)
		allowMethod(HttpMethod.Post)
	}

	routing {
		get("/health") {
			call.respond(mapOf("status" to "ok", "placeCount" to repository.all().size))
		}

		// Full place list for "click" mode: client shows the name, user clicks the map.
		// No coordinates are included, so the answer can't be read out of the response.
		get("/places") {
			call.respond(repository.all().map { it.toSummary() })
		}

		// Starts a "name" mode round: returns one random place's id + coordinates so the
		// client can drop a marker on the map. The NAME is deliberately omitted here —
		// the user has to guess it.
		get("/round/name-target") {
			val place = repository.randomPlace()
				?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No places loaded"))
			call.respond(
				mapOf(
					"placeId" to place.id,
					"lat" to place.lat,
					"lng" to place.lng,
					"province" to (place.province ?: "")
				)
			)
		}

		// Click-to-guess scoring.
		post("/guess/click") {
			val body = call.receive<ClickGuessRequest>()
			val place = repository.byId(body.placeId)
				?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown placeId"))

			val distance = GeoScoring.haversineKm(body.guessLat, body.guessLng, place.lat, place.lng)
			val score = GeoScoring.scoreForDistance(distance)
			val clickedProvince = ProvinceLookup.findProvince(body.guessLat, body.guessLng)

			call.respond(
				ClickGuessResponse(
					placeId = place.id,
					correctName = place.name,
					distanceKm = (distance * 10).roundToInt() / 10.0,
					score = score,
					correctLat = place.lat,
					correctLng = place.lng,
					clickedProvince = clickedProvince
				)
			)
		}

		// Starts a "province" mode round: returns one random place's id + name only.
		// The province is deliberately withheld — the user has to click it on the map.
		get("/round/province-target") {
			val place = repository.randomPlace()
				?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No places loaded"))
			call.respond(
				mapOf(
					"placeId" to place.id,
					"name" to place.name
				)
			)
		}

		// Type-the-name scoring.
		post("/guess/name") {
			val body = call.receive<NameGuessRequest>()
			val place = repository.byId(body.placeId)
				?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown placeId"))

			val (isCorrect, isClose, editDist) = NameMatching.checkGuess(body.guessName, place.name)

			call.respond(
				NameGuessResponse(
					placeId = place.id,
					correctName = place.name,
					isCorrect = isCorrect,
					isClose = isClose,
					distanceEdits = editDist
				)
			)
		}

		// Province-guess scoring. Province name matching is exact-but-lenient: case-insensitive,
		// whitespace-trimmed, and alias-aware (e.g. "Friesland" in place data is treated the
		// same as "Fryslân", the boundary data's official name — see ProvinceNames).
		post("/guess/province") {
			val body = call.receive<ProvinceGuessRequest>()
			val place = repository.byId(body.placeId)
				?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown placeId"))

			val correctProvince = ProvinceNames.canonicalize(place.province ?: "")
			val guessedProvince = ProvinceNames.canonicalize(body.guessedProvince)
			val isCorrect = guessedProvince.equals(correctProvince, ignoreCase = true)

			call.respond(
				ProvinceGuessResponse(
					placeId = place.id,
					correctProvince = correctProvince,
					isCorrect = isCorrect
				)
			)
		}
	}
}