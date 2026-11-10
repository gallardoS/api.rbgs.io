# F16 monitoring and local load tests

## Monitoring

The local monitoring stack runs a separate API and PostgreSQL with Prometheus and a provisioned Grafana dashboard. Configure the Battle.net credentials and matching callback for this local API, a local `RBGS_DB_PASSWORD`, and `RBGS_GRAFANA_PASSWORD` in the shell or in the Compose environment file.

```powershell
docker compose -f compose.monitoring.yaml up --build -d
```

Open [RBGS Preseason queues](http://localhost:3000/d/rbgs-preseason-queues) and sign in as `admin` with the configured Grafana password. The default API port is 8080. Set `RBGS_MONITORING_API_PORT` or `RBGS_GRAFANA_PORT` if the default host ports are occupied. The API and Grafana bind to loopback on the host. Prometheus and Actuator port 8081 are reachable on the Compose network.

The dashboard shows REST latency and response rates by normalized route, active SSE streams and distinct accounts, opens, closures by reason and invalidation writes, queue depth and wait at matching-cycle samples, proposals, matcher timings, JVM heap and the database connection pool. Premade depth counts teams. Both queues use the same SSE channel, so the stream panels report their combined activity. An SSE open can be an initial connection or a reconnect. The smoke test separately verifies recovery on reconnect by reading the latest authorized status.

```powershell
docker compose -f compose.monitoring.yaml down
```

## Twenty-account smoke test

Create an empty local PostgreSQL database named `rbgs_f16_smoke` for each run. The test uses a dedicated database URL on loopback and keeps fixture rows for inspection. Set the port, username and password for that database. A local server that requires a password also needs `RBGS_F16_DB_PASSWORD`.

```powershell
createdb -h 127.0.0.1 -p 55432 -U rbgs rbgs_f16_smoke
$env:RBGS_F16_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/rbgs_f16_smoke'
.\mvnw.cmd -B '-Dtest=PreseasonLoadSmokeTests' test
```

The test seeds 20 fictional Forever EU accounts and JDBC web sessions. The accounts use normal authenticated HTTP and SSE routes. The test invokes the matcher and expiry directly so it can isolate proposal delivery and advance deadlines without waiting. SoloQ compares warmed bursts with calls staggered by at least 75 ms. Premade uses two groups of ten and checks captain-only admission, cancellation on a roster change, rejection, deadline expiry and acceptance by all 20. Both queues check reservation release and state recovery on reconnect. The test also checks the private Prometheus scrape.

The run writes `target/f16-smoke.json` with client HTTP timings and server timings for the four SoloQ phases. The coordinator measurement is elapsed time in the PostgreSQL `SELECT FOR UPDATE` calls, including lock wait and SQL/JDBC overhead. The remaining server time includes the rest of the request. Each p95 is calculated from its own distribution. CI publishes this report as the `f16-smoke` artifact.

## SSE disconnect and recovery

Queue streams stay open by default. The API writes a transport-only comment every five seconds when it has no event to deliver. It revalidates the session and account before application events, including the regular 15-second ping.

Run the following against a fresh `rbgs_f16_sse` database on loopback. The test uses twenty raw HTTP connections, closes them with a normal TCP close and a TCP reset, and checks release and room recovery after reconnect. It also verifies that revoking a session closes its stream.

```powershell
createdb -h 127.0.0.1 -p 55432 -U rbgs rbgs_f16_sse
$env:RBGS_F16_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/rbgs_f16_sse'
$env:RBGS_F16_SSE_TIMEOUT_MS = '0'
.\mvnw.cmd -B '-Dtest=PreseasonLoadSmokeTests#queueStreamsRecoverAfterDisconnectOrExpiry' test
```

Repeat with a fresh database and `RBGS_F16_SSE_TIMEOUT_MS=3000` to exercise server timeout and recovery. The two modes write `target/f16-sse-transport.json` and `target/f16-sse-expiry.json`.

## Mixed queues under k6

Build k6 2.0.0 with xk6-sse 0.2.0. This Windows example uses xk6 1.4.15 in Docker. On Linux, use `--os linux` and remove the `.exe` suffix.

```powershell
New-Item -ItemType Directory -Force target | Out-Null
$f16Output = (Resolve-Path target).Path
docker run --rm --mount "type=bind,source=$f16Output,target=/xk6" grafana/xk6:1.4.15 build --os windows --arch amd64 v2.0.0 --with github.com/phymbert/xk6-sse@v0.2.0 --output /xk6/f16-k6.exe
$env:RBGS_F16_K6 = Join-Path $f16Output 'f16-k6.exe'
```

Select `quick`, `ramp` or `soak`. Create the corresponding empty database before each run. Use the same `RBGS_F16_DB_USER` and `RBGS_F16_DB_PASSWORD` settings as for the smoke test.

```powershell
$env:RBGS_F16_PROFILE = 'ramp'
$f16Database = "rbgs_f16_$env:RBGS_F16_PROFILE"
createdb -h 127.0.0.1 -p 55432 -U rbgs $f16Database
$env:RBGS_F16_DB_URL = "jdbc:postgresql://127.0.0.1:55432/$f16Database"
.\mvnw.cmd -B '-Dtest=PreseasonLoadSmokeTests#mixedQueuesUnderK6' test
```

- `quick` reaches 200 and holds for 90 seconds.
- `ramp` runs the 20, 50, 100 and 200 user stages and holds the peak for 10 minutes.
- `soak` ramps to 200 and holds for 30 minutes.

One k6 VU represents one account and keeps one authenticated SSE stream. HTTP actions run on ready, invalidation and heartbeat events. At peak the fixture supplies 100 SoloQ players and ten Premade groups. The scenario drives repeated proposals and acceptance, companion heartbeats, reconnect and leaderboard reads. A loopback test API and its dedicated PostgreSQL run separately from the Grafana stack. The harness deletes the temporary fixture file containing the synthetic sessions and device tokens at the end of each run.

The k6 JSON and log are in `target/f16-k6-<profile>.*`. The API and PostgreSQL samples and reservation checks are in `target/f16-telemetry-<profile>.json`. The harness checks that peak samples contain 200 streams for 200 distinct accounts, and checks stream and reservation cleanup and zero duplicate reservations. k6 exits unsuccessfully if the REST p95 by operation exceeds 500 ms or unexpected errors reach 1%. Results describe Preseason rehearsals in the local environment.

## Evidence burst and recovery

```powershell
createdb -h 127.0.0.1 -p 55432 -U rbgs rbgs_f16_evidence
$env:RBGS_F16_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/rbgs_f16_evidence'
.\mvnw.cmd -B '-Dtest=EvidenceBurstRecoveryTests' test
```

The burst fires 200 authenticated HTTP submissions together. It represents ten synthetic matches across account and character seasons, with 190 distinct reports, ten retries and ten withheld reports. This test configures a local Tomcat accept queue of 256 to accommodate the burst of new HTTP/1.1 connections. The test stops the first web application with the evidence jobs still persisted, starts a fresh one against the same PostgreSQL database, and verifies the worker processes the jobs, receipts remain idempotent, and only the eight concordant matches apply rating. The two review cohorts cover an expired incomplete roster and a contradictory outcome. The fixture uses a recognized diagnostic capture profile to exercise real verification and rating. The report `target/f16-evidence.json` records the payload size and acknowledgement p95 against the 1-second objective.
