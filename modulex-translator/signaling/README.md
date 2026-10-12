# ModuleX signaling relay

A minimal, privacy-preserving WebSocket relay that lets exactly two ModuleX Translator
phones exchange WebRTC SDP offers/answers and ICE candidates for **Internet mode**.

- It pairs two sockets that present the same room id (derived from the pairing code) and
  forwards `signal` payloads between them **without interpreting them**.
- Translation content never touches it: once WebRTC is up, text and audio flow
  peer-to-peer over the DTLS-encrypted data channel.
- It keeps no state beyond in-memory rooms, stores nothing on disk, has no analytics, and
  never logs payloads, client IPs or full room ids (debug lines carry an 8-hex-char SHA-256
  prefix at most; INFO logs only room/connection counts).

Code: `src/main/kotlin/com/modulex/translator/signaling/` (Ktor 3 + Netty, about 600 lines).

## Protocol v1

The Android client is written against this spec.

### Endpoint

`GET /v1/rooms/{room}` (WebSocket upgrade), where `room` matches `^[A-Za-z0-9_-]{16,64}$`.

Checked **before** the upgrade, in this order:

| Condition | HTTP response |
|---|---|
| More than `JOIN_RATE_PER_MINUTE` attempts from this client IP in the last minute (invalid room ids count too) | `429` with body `{"type":"error","code":"rate_limited"}` |
| Room id does not match the pattern | `400` with body `{"type":"error","code":"bad_room"}` |
| Valid room id but no WebSocket handshake | `426` with body `{"type":"error","code":"bad_frame"}` |

`GET /healthz` → `200 ok` (text/plain).

### Frames

All frames are UTF-8 JSON **text** frames.

Server → client:

| Message | When |
|---|---|
| `{"type":"joined","peers":N}` | On join; `N` = participants now in the room including this one (1 or 2). |
| `{"type":"peer-joined"}` | To the already-present peer when a second one joins. |
| `{"type":"peer-left"}` | When the other peer disconnects. |
| `{"type":"signal","payload":<value>}` | Relayed signal; `payload` is exactly what the sender put in `payload`. |
| `{"type":"pong"}` | Reply to a client `ping`. |
| `{"type":"error","code":"<code>"}` | `room_full`, `bad_room`, `bad_frame`, `too_large`, `rate_limited`, `no_peer`, `server_full`. |

Client → server:

| Message | Effect |
|---|---|
| `{"type":"signal","payload":<any JSON value>}` | Relayed verbatim to the other participant. No other participant → error `no_peer` (socket stays open). |
| `{"type":"ping"}` | Server replies `{"type":"pong"}`. |
| Anything else / unparseable | Error `bad_frame`; the socket stays open for the first 4, the 5th bad frame gets `bad_frame` and close **1008**. |

Errors that close the socket (the error frame is always sent before the close frame):

| Situation | Error | Close code |
|---|---|---|
| Third participant | `room_full` | 1008 |
| Room limit (`MAX_ROOMS`) reached when creating a new room | `server_full` | 1013 (try again later) |
| Binary frame | `bad_frame` | 1003 |
| Text frame larger than `MAX_FRAME_BYTES` | `too_large` | 1009 |
| More than `MSG_RATE_PER_10S` messages in any 10 s window | `rate_limited` | 1008 |
| 5th bad frame | `bad_frame` | 1008 |
| Room idle for `ROOM_IDLE_SECONDS` | (none) | 1001 on both sockets; room dropped |

Other behaviour:

- Max 2 participants per room. Joins are serialized, so two peers joining at the same
  moment always get `peers:1` and `peers:2`, and `joined` is always delivered before any
  relayed `signal`.
- An empty room is removed immediately; a peer can then rejoin the same room id.
- "Activity" for the idle timeout is any accepted client `signal` or `ping` (WebSocket-level
  pings do not count).
- WebSocket-level ping every 20 s; a peer silent for 30 s is dropped.
- `payload` is relayed as the same JSON value (structure, key order, numbers as written,
  strings decoded and re-encoded). Payloads nested deeper than 64 levels are rejected as
  `bad_frame` (guard against stack exhaustion in the JSON parser; real signals nest 2–3 levels).
- A client that stops reading while its queue of 256 pending frames is full is closed with 1013.
- Frames above 4 × `MAX_FRAME_BYTES` are cut off by Ktor itself (close 1009 without an error frame).

## Configuration

All configuration comes from environment variables (see [`.env.example`](.env.example)).

| Variable | Default | Meaning |
|---|---|---|
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8080` | Plain HTTP/WebSocket port |
| `MAX_FRAME_BYTES` | `16384` | Largest accepted text frame |
| `MAX_ROOMS` | `10000` | Concurrent rooms |
| `ROOM_IDLE_SECONDS` | `600` | Idle room timeout |
| `JOIN_RATE_PER_MINUTE` | `30` | Join attempts per client IP per minute (slows down pairing-code guessing) |
| `MSG_RATE_PER_10S` | `50` | Messages per connection per 10 s |
| `TRUST_PROXY_HEADERS` | `false` | `true`: client IP = first `X-Forwarded-For` value. Enable only behind your own proxy. |
| `TLS_PORT` | unset | If set, also serve HTTPS/WSS on this port |
| `TLS_KEYSTORE_PATH` | — | PKCS12/JKS keystore (required with `TLS_PORT`) |
| `TLS_KEYSTORE_PASSWORD` | — | Keystore and key password (required with `TLS_PORT`) |
| `TLS_KEY_ALIAS` | — | Key alias (required with `TLS_PORT`) |

Secrets are read only from the environment. Never commit a `.env` or keystore.

## Running locally

```sh
# from the repository root
./gradlew :signaling:test            # protocol tests
./gradlew :signaling:run             # listens on :8080
curl localhost:8080/healthz          # -> ok

# or a standalone distribution
./gradlew :signaling:installDist
PORT=9000 signaling/build/install/signaling/bin/signaling
```

Quick manual check with [websocat](https://github.com/vi/websocat):

```sh
websocat ws://localhost:8080/v1/rooms/testroom_0123456789   # in two terminals
{"type":"signal","payload":{"hello":"world"}}
```

The app itself refuses `ws://`; plain WebSocket is for local testing only.

### Docker

```sh
# build from the repository root (multi-project Gradle build)
docker build -f signaling/Dockerfile -t modulex-signaling .
docker run --rm -p 127.0.0.1:8080:8080 --env-file signaling/.env modulex-signaling
```

The image runs as an unprivileged user (uid 10001) on `eclipse-temurin:17-jre`.

## Deploying behind a TLS reverse proxy

**`wss://` is required: the app refuses `ws://`.** The recommended setup is the relay on
plain HTTP bound to localhost (or a private network) and a reverse proxy terminating TLS.
Set `TRUST_PROXY_HEADERS=true` so rate limits use the real client IP, and make sure the
relay port is not reachable from the Internet directly (otherwise clients could spoof
`X-Forwarded-For`).

### Caddy (automatic Let's Encrypt certificates)

```caddyfile
signal.example.org {
    @relay path /v1/rooms/* /healthz
    handle @relay {
        reverse_proxy 127.0.0.1:8080 {
            # Overwrite, never append, so clients cannot inject their own value.
            header_up X-Forwarded-For {remote_host}
        }
    }
    handle {
        respond 404
    }
    log {
        # Room ids are in the URL path; keep access logs off or strip the path.
        output discard
    }
}
```

Caddy proxies WebSocket upgrades automatically.

### nginx

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

server {
    listen 443 ssl;
    server_name signal.example.org;
    ssl_certificate     /etc/letsencrypt/live/signal.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/signal.example.org/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log off;                  # URLs contain room ids

    location /v1/rooms/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header X-Forwarded-For $remote_addr;   # overwrite, don't append
        proxy_read_timeout 60s;      # > the relay's 20 s ping period
        proxy_buffering off;
    }
    location = /healthz { proxy_pass http://127.0.0.1:8080; }
    location / { return 404; }
}
```

### Built-in TLS (no proxy)

Set `TLS_PORT`, `TLS_KEYSTORE_PATH`, `TLS_KEYSTORE_PASSWORD` and `TLS_KEY_ALIAS` to serve
`wss://` directly from the relay, e.g. from a Let's Encrypt certificate:

```sh
openssl pkcs12 -export -name relay -in fullchain.pem -inkey privkey.pem -out relay.p12
```

You must then restart the relay on every certificate renewal; a reverse proxy is simpler.

## What the operator can see

Whoever runs the relay (or can read its memory/traffic behind the TLS terminator) can see:

- **Client IP addresses** and **connection timing** (when two phones paired, for how long).
- **Room ids** (derived from the pairing code).
- **SDP and ICE candidates**, which include the phones' **local and public IP addresses**
  and **DTLS certificate fingerprints**.

The operator can **not** see translation content (text, audio, transcripts). It flows
peer-to-peer over the WebRTC data channel, encrypted end-to-end with DTLS, and the session
is additionally authenticated by HMAC proofs bound to both DTLS fingerprints and keyed with
the pairing session secret, which never passes through the relay. A malicious relay that
substitutes its own SDP (to man-in-the-middle the DTLS session) cannot produce valid proofs
without that secret, so the app detects it and aborts.

This relay itself logs nothing beyond counts; a reverse proxy may log more (disable access
logs as shown above).

## TURN

Direct peer-to-peer connections fail behind some strict/symmetric NATs and carrier-grade
NATs. In that case a **TURN server** (for example [coturn](https://github.com/coturn/coturn))
is needed to relay the encrypted media/data. Configure the TURN URL and credentials in the
app settings. TURN only ever sees DTLS-encrypted packets.

**Never ship TURN credentials inside the APK**: anyone could extract them and use your
TURN server as a free bandwidth relay. Use per-user configuration or short-lived
credentials (coturn `use-auth-secret` / REST API credentials).

## Security hardening

- Serve only over TLS (`wss://`); keep the plain port bound to localhost or a private network.
- Set `TRUST_PROXY_HEADERS=true` only when every request passes through your own proxy, and
  have the proxy **overwrite** `X-Forwarded-For`.
- Keep room ids long and random (the app derives them from the pairing code); the per-IP
  join limit only slows down guessing. Lower `JOIN_RATE_PER_MINUTE` if you see abuse.
- Disable access logs on the proxy, or at least strip `/v1/rooms/...` paths.
- Run the container as non-root (the image already does), read-only root filesystem,
  `--cap-drop=ALL`, `--security-opt no-new-privileges`, and a memory limit
  (`JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`).
- Add network-level protection (firewall, connection limits per IP on the proxy, e.g. nginx
  `limit_conn`) for volumetric abuse; the relay's own limits are per connection and per IP.
- Keep the JDK base image and Ktor up to date.
