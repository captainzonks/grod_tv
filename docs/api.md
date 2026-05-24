# grod_tv HTTP API Reference

The `grod_tv` Android TV app hosts a Ktor CIO server on port `7878` (configurable in code, not yet user-configurable) that mirrors the `grod` Rust daemon's API byte-for-byte. The phone `grod_remote` app and the Rust daemon's own clients drive both endpoints interchangeably.

- **Base URL:** `http://<tv-lan-ip>:7878`
- **Content type:** `application/json` (request and response)
- **Auth:** optional `X-Grod-Pin: <pin>` header. If the PIN is empty (default), auth is bypassed; if set, every endpoint below except `/ping` requires the matching header.
- **mDNS:** the service is advertised as `_grod._tcp.local.` with TXT records `version=<crate version>`, `pin=<0|1>`, `device=grod-tv`.

---

## Conventions

- All positions in request/response bodies are **1-based** (matches the Rust daemon's wire format). Internal storage is 0-based; the translation happens in `QueueRepository`.
- All durations and positions in `/status` are integer seconds.
- Errors return a JSON body `{"error":"<message>"}` with the appropriate HTTP status. There is no error code field.

---

## Endpoints

### `GET /ping`

Liveness probe. No auth required. Returns the bare-string `"pong"` with `200 OK`.

```bash
$ curl -sS http://192.168.1.42:7878/ping
pong
```

---

### `GET /status`

Snapshot of player + queue state. Used by `grod_remote` for the now-playing screen and as a heartbeat.

```bash
$ curl -sS http://192.168.1.42:7878/status
{
  "state": "playing",
  "now_playing": {
    "id": "dQw4w9WgXcQ",
    "title": "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)"
  },
  "queue": [
    {"pos": 1, "video_id": "9bZkp7q19f0", "title": "PSY - GANGNAM STYLE"}
  ],
  "daemon": true,
  "quality": "1080p",
  "position": 42,
  "duration": 213,
  "piped_url": "https://pipedapi.kavin.rocks"
}
```

| Field         | Type        | Notes                                                                  |
| ------------- | ----------- | ---------------------------------------------------------------------- |
| `state`       | string      | `idle \| buffering \| playing \| paused`                               |
| `now_playing` | object/null | `{id, title}` or `null` if nothing has been loaded                     |
| `queue`       | array       | `[{pos, video_id, title}, …]`, sorted by ascending 1-based position    |
| `daemon`      | bool        | always `true` — kept for parity with Rust daemon's `/status`           |
| `quality`     | string      | label of currently loaded video (`1080p`, `360p`, …) or settings default if idle |
| `position`    | int/null    | seconds into the current track, or `null` if no media loaded           |
| `duration`    | int/null    | total seconds, or `null` if unknown                                    |
| `piped_url`   | string/null | Piped API base URL the daemon is currently configured to use. `null` on daemons older than the `/piped-url` endpoint. |

> **Known issue:** when the player is in `PlaybackPhase.Error`, `state` is currently reported as `idle`. Tracked for a follow-up.

---

### `POST /cast`

Start playback now, or queue if busy.

```bash
$ curl -X POST http://192.168.1.42:7878/cast \
       -H 'Content-Type: application/json' \
       -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}'
```

Request body:

| Field   | Type   | Required | Notes                                                              |
| ------- | ------ | -------- | ------------------------------------------------------------------ |
| `url`   | string | yes      | YouTube URL, Piped URL, `youtu.be/<id>`, or bare 11-char video ID  |
| `force` | bool   | no       | If `true` and the player is busy, stop, clear now-playing, and re-resolve at the current default quality |

Responses:

- **200 OK** (`force=true`, or player was idle) — `{"casting":true,"title":"…","quality":"1080p"}`
- **200 OK** (player was busy, no `force`) — `{"pos":3,"title":"…"}` (queued)
- **400 Bad Request** — could not extract a video ID
- **500 Internal Server Error** — Piped resolve or title fetch failed

> **`force=true` semantics:** when forcing playback, the daemon stops the
> player AND clears the now-playing row before re-resolving. This guarantees
> that re-casting the same URL after raising the default quality picks up
> the new value rather than silently replaying the cached state.

---

### `POST /queue`

Append a video to the queue without disturbing playback.

```bash
$ curl -X POST http://192.168.1.42:7878/queue \
       -H 'Content-Type: application/json' \
       -d '{"url":"https://www.youtube.com/watch?v=9bZkp7q19f0"}'
{"pos":1,"title":"PSY - GANGNAM STYLE(강남스타일) M/V"}
```

| Status              | Body                                  |
| ------------------- | ------------------------------------- |
| 200 OK              | `{"pos": <1-based>, "title": "…"}`    |
| 400 Bad Request     | `{"error":"could not extract video ID"}` |
| 500 Internal Error  | `{"error":"<piped title fetch error>"}`  |

---

### `DELETE /queue/{pos}`

Remove a single queue entry by 1-based position.

```bash
$ curl -X DELETE http://192.168.1.42:7878/queue/2
{"removed":"PSY - GANGNAM STYLE"}
```

Returns `404` if the position is out of range. After removal, all trailing positions are shifted down by one (the `reindexAfterRemoval` Room transaction).

---

### `DELETE /queue`

Clear the entire queue. Does **not** stop the current track; use `/skip` for that.

```bash
$ curl -X DELETE http://192.168.1.42:7878/queue
{"ok":true}
```

---

### `POST /skip`

Stop the current track and clear the now-playing pointer.

```bash
$ curl -X POST http://192.168.1.42:7878/skip
{"ok":true}
```

Internally this calls `PlayerController.stop()` (which transitions to `PlaybackPhase.Idle`) and `QueueRepository.clearNowPlaying()`. `AutoAdvancer` is wired to `Player.STATE_ENDED` only, not `STATE_IDLE`, so a manual skip won't auto-load the next track until you call `/cast` again. (This is a deliberate semantic divergence from `grod` — we may align it in a follow-up.)

> **Note:** prior to v0.1.3 `/skip` left the previous track's title visible in `/status.now_playing` until the next cast resolved. Now it is cleared atomically with the player stop.

---

### `POST /play-pause`

Toggle. Pauses if playing, plays if paused, no-op otherwise.

---

### `POST /forward` / `POST /back`

Seek relative.

```bash
$ curl -X POST http://192.168.1.42:7878/forward \
       -H 'Content-Type: application/json' \
       -d '{"seconds":30}'
{"ok":true}
```

Body is optional; defaults to 10 seconds. Seeks clamp at 0 (no negative positions).

---

### `POST /volume-up` / `POST /volume-down`

Adjust the ExoPlayer instance volume by ±0.05 (clamped to `[0.0, 1.0]`). Does not touch system volume — for that, use `KEYCODE_VOLUME_UP` via ADB or the TV remote.

---

### `POST /mute` / `POST /unmute`

Set ExoPlayer volume to `0.0` / `1.0` directly.

---

### `GET /search?q=...`

Pass-through to Piped's `/search?q=…&filter=videos`.

```bash
$ curl -sS 'http://192.168.1.42:7878/search?q=rick+astley' | jq '.[0]'
{
  "url": "/watch?v=dQw4w9WgXcQ",
  "title": "Rick Astley - Never Gonna Give You Up (Official Video)",
  "uploader": "Rick Astley",
  "duration": 213,
  "thumbnail": "https://…"
}
```

Returns a JSON array. `url` is the Piped-relative path; resolve against your Piped instance, or hand it back to `/cast` unchanged (the URL extractor handles relative `/watch?v=…` forms via the bare ID it extracts).

---

### `POST /quality`

Change the default cast quality (persisted to DataStore).

```bash
$ curl -X POST http://192.168.1.42:7878/quality \
       -H 'Content-Type: application/json' \
       -d '{"quality":"720p"}'
{"quality":"720p"}
```

Valid values: `best`, `1080p`, `720p`, `480p`, `360p`. Other strings return `400`. Existing in-flight playback is not affected; the new quality applies to subsequent `/cast` and auto-advance loads.

---

### `POST /piped-url`

Repoint the daemon at a different Piped instance. Persisted to DataStore. The internal `PipedClient` is rebuilt automatically; the next `/cast` or `/search` uses the new base URL.

```bash
$ curl -X POST http://192.168.1.42:7878/piped-url \
       -H 'Content-Type: application/json' \
       -d '{"url":"https://pipedapi.adminforge.de"}'
{"piped_url":"https://pipedapi.adminforge.de"}
```

| Status              | Body                                                                                 |
| ------------------- | ------------------------------------------------------------------------------------ |
| 200 OK              | `{"piped_url": "<trimmed url>"}`                                                     |
| 400 Bad Request     | `{"error":"piped url must start with http:// or https://"}` if the scheme is missing |

Trailing slashes are stripped before persisting so the same canonical URL surfaces in `/status.piped_url` regardless of how the caller formatted it.

Primarily used by `grod_remote` so the user can swap instances from their phone instead of typing a long URL on the TV's soft keyboard.

---

## CORS

The server installs Ktor's `CORS` plugin with `anyHost()` and allows `Content-Type` and `X-Grod-Pin` headers plus `GET`/`POST`/`DELETE` methods. Suitable for a same-LAN web remote; **do not** expose port 7878 to the WAN.

---

## Reference

- Implementation: [`app/src/main/java/com/captainzonks/grodtv/api/ApiServer.kt`](../app/src/main/java/com/captainzonks/grodtv/api/ApiServer.kt)
- DTOs: [`app/src/main/java/com/captainzonks/grodtv/api/ApiDtos.kt`](../app/src/main/java/com/captainzonks/grodtv/api/ApiDtos.kt)
- Foreground service host: [`app/src/main/java/com/captainzonks/grodtv/api/ApiService.kt`](../app/src/main/java/com/captainzonks/grodtv/api/ApiService.kt)
