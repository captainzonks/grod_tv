package com.captainzonks.grodtv.api

import com.captainzonks.grodtv.AppContainer
import com.captainzonks.grodtv.piped.Quality
import com.captainzonks.grodtv.piped.extractVideoId
import com.captainzonks.grodtv.player.PlaybackPhase
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

private val ApiPin = AttributeKey<String>("ApiPin")

class ApiServer(
    private val container: AppContainer,
    private val port: Int,
    private val pin: String,
) {
    @Volatile
    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            configure(container, pin)
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
    }
}

private fun Application.configure(c: AppContainer, pin: String) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Grod-Pin")
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowMethod(io.ktor.http.HttpMethod.Get)
    }

    routing {
        get("/ping") {
            call.respond(io.ktor.http.HttpStatusCode.OK, "pong")
        }
        get("/status") {
            if (!call.authOk(pin)) return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val pState = c.playerController.state.value
            val state = when (pState.phase) {
                PlaybackPhase.Playing -> "playing"
                PlaybackPhase.Paused -> "paused"
                PlaybackPhase.Buffering -> "buffering"
                PlaybackPhase.Error -> "error"
                PlaybackPhase.Ended, PlaybackPhase.Idle -> "idle"
            }
            val nowPlaying = c.queueRepository.getNowPlaying()
            val queue = c.queueRepository.list().map { QueueEntryDto(it.pos, it.videoId, it.title) }
            val settings = c.settings.value
            val quality = pState.qualityLabel ?: settings.defaultQuality.label
            val position = c.playerController.currentPositionSecs()
            val duration = pState.durationSecs

            call.respond(
                StatusResponse(
                    state = state,
                    nowPlaying = nowPlaying?.let { NowPlayingDto(it.videoId, it.title) },
                    queue = queue,
                    daemon = true,
                    quality = quality,
                    position = position,
                    duration = duration,
                )
            )
        }

        post("/cast") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val body = call.receive<UrlBody>()
            val id = extractVideoId(body.url)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("could not extract video ID"))

            val client = c.pipedClient.value
            val occupied = c.playerController.isOccupied()

            if (occupied && !body.force) {
                client.title(id)
                    .onFailure { return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "title failed")) }
                    .onSuccess { title ->
                        val pos = c.queueRepository.push(id, title)
                        call.respond(CastQueuedResponse(pos = pos, title = title))
                    }
                return@post
            }

            if (occupied && body.force) c.playerController.stop()

            val quality = c.settings.value.defaultQuality
            client.resolve(id, quality)
                .onFailure { return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "resolve failed")) }
                .onSuccess { video ->
                    c.playerController.load(video)
                    c.queueRepository.setNowPlaying(video.id, video.title)
                    call.respond(CastResponse(title = video.title, quality = video.qualityLabel))
                }
        }

        post("/queue") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val body = call.receive<UrlBody>()
            val id = extractVideoId(body.url)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("could not extract video ID"))
            c.pipedClient.value.title(id)
                .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "title failed")) }
                .onSuccess { title ->
                    val pos = c.queueRepository.push(id, title)
                    call.respond(QueuedResponse(pos = pos, title = title))
                }
        }

        delete("/queue/{pos}") {
            if (!call.authOk(pin)) return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val pos = call.parameters["pos"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid position"))
            val removed = c.queueRepository.removeAt(pos)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("position out of range"))
            call.respond(RemovedResponse(removed = removed.title))
        }

        delete("/queue") {
            if (!call.authOk(pin)) return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.queueRepository.clear()
            call.respond(OkResponse())
        }

        post("/skip") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.playerController.stop()
            call.respond(OkResponse())
        }

        post("/play-pause") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.playerController.togglePlayPause()
            call.respond(OkResponse())
        }

        post("/volume-up") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.playerController.volumeUp()
            call.respond(OkResponse())
        }

        post("/volume-down") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.playerController.volumeDown()
            call.respond(OkResponse())
        }

        post("/mute") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.playerController.mute()
            call.respond(OkResponse())
        }

        post("/unmute") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            c.playerController.unmute()
            call.respond(OkResponse())
        }

        post("/forward") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val secs = call.receiveNullable<SeekBody>()?.seconds ?: 10
            c.playerController.seekForward(secs.toLong())
            call.respond(OkResponse())
        }

        post("/back") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val secs = call.receiveNullable<SeekBody>()?.seconds ?: 10
            c.playerController.seekBack(secs.toLong())
            call.respond(OkResponse())
        }

        get("/search") {
            if (!call.authOk(pin)) return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val q = call.request.queryParameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing q"))
            c.pipedClient.value.search(q)
                .onFailure { call.respond(HttpStatusCode.InternalServerError, ErrorResponse(it.message ?: "search failed")) }
                .onSuccess { results ->
                    call.respond(results.map {
                        SearchResultDto(
                            url = it.url, title = it.title, uploader = it.uploader,
                            duration = it.duration, thumbnail = it.thumbnail,
                        )
                    })
                }
        }

        post("/quality") {
            if (!call.authOk(pin)) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid PIN"))
            val body = call.receive<QualityBody>()
            val q = Quality.parse(body.quality)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid quality '${body.quality}': use best|1080p|720p|480p|360p"))
            c.settingsStore.setDefaultQuality(q)
            call.respond(QualitySetResponse(quality = q.label))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.authOk(pin: String): Boolean {
    if (pin.isEmpty()) return true
    val provided = request.headers["X-Grod-Pin"].orEmpty()
    return provided == pin
}
