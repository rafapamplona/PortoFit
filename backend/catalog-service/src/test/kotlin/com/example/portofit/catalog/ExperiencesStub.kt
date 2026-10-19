package com.example.portofit.catalog

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Falso experiences-service para `GET /api/v1/ratings/places` (docs/experiences-api.md §4.6).
 * [mode] decide a resposta; [requests] guarda a query de cada pedido recebido.
 */
class ExperiencesStub private constructor(private val server: HttpServer) {

	enum class Mode { UP, ERROR, SLOW }

	@Volatile
	var mode = Mode.ERROR

	/** Avaliações devolvidas em modo UP, como JSON do objeto `rating`; os ids ausentes vêm NO_RATINGS. */
	val placeRatings = mutableMapOf<String, String>()

	/** Avaliações de atividades em modo UP: id da atividade → (id do local, JSON do `rating`). */
	val offeringRatings = mutableMapOf<String, Pair<String, String>>()

	val requests = CopyOnWriteArrayList<String>()

	val url: String get() = "http://127.0.0.1:${server.address.port}"

	fun reset(mode: Mode) {
		this.mode = mode
		placeRatings.clear()
		offeringRatings.clear()
		requests.clear()
	}

	private fun body(query: String): String {
		val params = query.split("&").associate {
			val (k, v) = it.split("=", limit = 2)
			k to URLDecoder.decode(v, Charsets.UTF_8)
		}
		val includeOfferings = params["includeOfferings"] == "true"
		val items = params.getValue("ids").split(",").joinToString(",") { placeId ->
			val rating = placeRatings[placeId] ?: """{"status":"NO_RATINGS","average":null,"ratingsCount":0}"""
			val offerings = if (includeOfferings) {
				""","offerings":[""" + offeringRatings.filterValues { it.first == placeId }.entries.joinToString(",") { (id, value) ->
					"""{"offeringId":"$id","rating":${value.second}}"""
				} + "]"
			} else ""
			"""{"placeId":"$placeId","rating":$rating$offerings}"""
		}
		return """{"items":[$items]}"""
	}

	companion object {
		fun start(): ExperiencesStub {
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			val stub = ExperiencesStub(server)
			server.createContext("/api/v1/ratings/places") { exchange ->
				val query = exchange.requestURI.rawQuery.orEmpty()
				stub.requests += query
				val (status, body) = when (stub.mode) {
					Mode.UP -> 200 to stub.body(query)
					Mode.ERROR -> 503 to """{"status":503}"""
					Mode.SLOW -> {
						Thread.sleep(3000)
						200 to stub.body(query)
					}
				}
				val bytes = body.toByteArray(Charsets.UTF_8)
				exchange.responseHeaders.add("Content-Type", "application/json")
				runCatching {
					exchange.sendResponseHeaders(status, bytes.size.toLong())
					exchange.responseBody.use { it.write(bytes) }
				}
				exchange.close()
			}
			server.executor = java.util.concurrent.Executors.newCachedThreadPool()
			server.start()
			return stub
		}
	}
}
