package com.example.fireremoteplayer.remote

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

interface RemoteCommandHandler {
    fun load(url: String, autoPlay: Boolean)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
}

class HttpRemoteServer(
    private val port: Int,
    private val handler: RemoteCommandHandler,
    private val statusProvider: () -> PlayerStatus,
    private val pinProvider: () -> String
) {
    private var server: ApplicationEngine? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, module = ::configure).start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1_500)
        server = null
    }

    private fun configure(application: Application) {
        with(application) {
            install(ContentNegotiation) {
                json()
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-PIN")
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
            }

            routing {
                get("/") {
                    val expectedPin = pinProvider().trim()
                    val providedPin = call.request.queryParameters["pin"].orEmpty().trim()
                    if (expectedPin.isNotEmpty() && providedPin != expectedPin) {
                        call.respondText(loginPageHtml(), ContentType.Text.Html)
                        return@get
                    }
                    call.respondText(remotePageHtml(providedPin), ContentType.Text.Html)
                }

                get("/api/status") {
                    if (!authorizeApiCall()) return@get
                    call.respond(statusProvider())
                }

                post("/api/load") {
                    if (!authorizeApiCall()) return@post
                    val req = call.receive<LoadRequest>()
                    if (req.url.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "url is required"))
                        return@post
                    }
                    handler.load(req.url.trim(), req.autoPlay)
                    call.respond(ApiResponse(true, "stream loaded"))
                }

                post("/api/play") {
                    if (!authorizeApiCall()) return@post
                    handler.play()
                    call.respond(ApiResponse(true, "playing"))
                }

                post("/api/pause") {
                    if (!authorizeApiCall()) return@post
                    handler.pause()
                    call.respond(ApiResponse(true, "paused"))
                }

                post("/api/stop") {
                    if (!authorizeApiCall()) return@post
                    handler.stop()
                    call.respond(ApiResponse(true, "stopped"))
                }

                post("/api/seek") {
                    if (!authorizeApiCall()) return@post
                    val req = call.receive<SeekRequest>()
                    handler.seek(req.positionMs)
                    call.respond(ApiResponse(true, "seeked"))
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorizeApiCall(): Boolean {
        if (isAuthorized(this)) return true
        respond(HttpStatusCode.Unauthorized, ApiResponse(false, "invalid pin"))
        return false
    }

    private fun isAuthorized(call: io.ktor.server.application.ApplicationCall): Boolean {
        val expectedPin = pinProvider().trim()
        if (expectedPin.isEmpty()) return true

        val providedPin = call.request.headers["X-PIN"]
            ?.trim()
            .orEmpty()
            .ifEmpty { call.request.queryParameters["pin"].orEmpty().trim() }

        return providedPin == expectedPin
    }

    private fun loginPageHtml(): String =
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1" />
          <title>Fire Remote Player Login</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif; max-width: 480px; margin: 40px auto; padding: 0 12px; }
            .box { border: 1px solid #d6d6d6; border-radius: 10px; padding: 14px; }
            input { width: 100%; box-sizing: border-box; padding: 10px; font-size: 16px; margin-top: 8px; }
            button { margin-top: 10px; padding: 10px 14px; font-size: 15px; border-radius: 8px; border: 1px solid #444; background: #fafafa; }
            .hint { color: #666; margin-top: 8px; }
          </style>
        </head>
        <body>
          <h1>Fire Remote Player</h1>
          <div class="box">
            <p>Enter tablet PIN to continue.</p>
            <form method="GET" action="/">
              <input name="pin" inputmode="numeric" autocomplete="one-time-code" placeholder="PIN" />
              <button type="submit">Open Remote</button>
            </form>
            <p class="hint">Check the PIN shown on the tablet screen.</p>
          </div>
        </body>
        </html>
        """.trimIndent()

    private fun remotePageHtml(pin: String): String {
        val safePin = pin.replace("'", "\\'")
        return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1" />
          <title>Fire Remote Player</title>
          <style>
            body { font-family: -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif; max-width: 680px; margin: 24px auto; padding: 0 12px; }
            h1 { margin-bottom: 8px; }
            .row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
            input { flex: 1; min-width: 280px; padding: 10px; font-size: 16px; }
            button { padding: 10px 14px; font-size: 15px; border-radius: 8px; border: 1px solid #444; background: #fafafa; }
            #status { white-space: pre-wrap; background: #f1f1f1; border-radius: 8px; padding: 12px; }
          </style>
        </head>
        <body>
          <h1>Fire Remote Player</h1>
          <p>Control playback on your Fire tablet.</p>

          <div class="row">
            <input id="url" placeholder="https://example.com/stream.m3u8" />
            <button onclick="loadStream()">Load</button>
          </div>

          <div class="row">
            <button onclick="send('/api/play')">Play</button>
            <button onclick="send('/api/pause')">Pause</button>
            <button onclick="send('/api/stop')">Stop</button>
            <button onclick="seekBy(-10000)">-10s</button>
            <button onclick="seekBy(10000)">+10s</button>
          </div>

          <h3>Status</h3>
          <div id="status">loading...</div>

          <script>
            const pin = '${safePin}';

            async function send(path, body) {
              const response = await fetch(path, {
                method: 'POST',
                headers: {
                  'Content-Type': 'application/json',
                  'X-PIN': pin
                },
                body: body ? JSON.stringify(body) : undefined
              });
              if (!response.ok) {
                document.getElementById('status').textContent = 'Unauthorized. Re-open the remote page and enter PIN.';
                return;
              }
              await refresh();
            }

            async function loadStream() {
              const url = document.getElementById('url').value.trim();
              if (!url) return;
              await send('/api/load', { url, autoPlay: true });
            }

            async function seekBy(delta) {
              const statusRes = await fetch('/api/status', { headers: { 'X-PIN': pin } });
              if (!statusRes.ok) return;
              const s = await statusRes.json();
              const next = Math.max(0, (s.positionMs || 0) + delta);
              await send('/api/seek', { positionMs: next });
            }

            async function refresh() {
              const statusRes = await fetch('/api/status', { headers: { 'X-PIN': pin } });
              if (!statusRes.ok) {
                document.getElementById('status').textContent = 'Unauthorized. Re-open the remote page and enter PIN.';
                return;
              }
              const s = await statusRes.json();
              document.getElementById('status').textContent = JSON.stringify(s, null, 2);
              if (s.streamUrl && !document.getElementById('url').value) {
                document.getElementById('url').value = s.streamUrl;
              }
            }

            setInterval(refresh, 1500);
            refresh();
          </script>
        </body>
        </html>
        """.trimIndent()
    }
}
