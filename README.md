# UPI Offline Mesh Payment System

A Spring Boot **simulation** of a UPI-like payment created while the phone has **no internet**, carried across a **simulated device mesh**, and **settled later** when one or more connectivity bridges upload it.

This is **not** NPCI/UPI, not Bluetooth, and not a production bank. Unreliable delivery is the point of the product: copies can arrive twice, connectivity can be late, and the ledger must still stay consistent.

```
offline device composes a signed+encrypted payment
        ↓
packet is IN THE MESH and NOT SETTLED
        ↓
controlled flooding across nearby simulated devices
        ↓
connectivity still missing → still not settled
        ↓
one or more bridges regain internet and upload
        ↓
at-least-once delivery, at-most-one ledger effect
```

## Quick start

JDK 17+. From the project directory:

```bash
./mvnw spring-boot:run          # Mac/Linux
mvnw.cmd spring-boot:run        # Windows
```

Open http://localhost:8080

Demo flow:

1. Create a payment (Alice → Bob). The **In flight** table shows it is **not settled**.
2. Click **Forward one hop** three times so both bridges hold a copy. Still not settled.
3. Click **Upload from both bridges**. One ingest is `SETTLED`, the other `DUPLICATE`.
4. Open **Correctness Lab** and run `delayed_bridge` and `concurrent_cover_one`.

Optional Postgres (does not replace the default H2 demo):

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=postgres
```

Tests (H2 in-memory, no Docker). They do **not** prove PostgreSQL-specific locking:

```bash
./mvnw -B test
```

## Why this is hard

If the payer is offline, the receiver cannot get a live authorization from a bank. Until a bridge uploads the packet, **nothing has settled** — the humans are holding an IOU. Copies of that IOU can show up twice (two bridges). The payer can also create a *second* `payment_id` while still offline (**offline double-spend**). Idempotency does not prevent that; it only prevents the *same* authenticated intent from settling twice. The interesting engineering is deferred settlement under those conditions.

## Architecture (what this repo implements)

| Layer | What it does |
|---|---|
| Simulated device | Builds `payment_id`, signs with Ed25519, encrypts to the server |
| Mesh | Static graph, per-device seen set, TTL, **controlled flooding** |
| Bridges | Two nodes with `hasInternet=true`; flush uploads in parallel |
| Ingest | Decrypt + AAD + signature + freshness, then settle |
| Ledger | One `transactions` row per `payment_id`; debit+credit in one DB transaction |
| Delivery attempts | Every ingest is stored, including duplicates |

```
phone-alice
     |
phone-relay ---- phone-market
     |                 |
phone-bridge-1   phone-bridge-2
     \                 /
         Spring Boot
              |
     H2 (default) or PostgreSQL
```

### Identifiers

- `payment_id` — financial intent. Unique on the ledger.
- `packet_hash` — SHA-256 of the **decoded ciphertext bytes**. Unique on the ledger. Answers “have I seen this exact encrypted blob?”

Two bridges carrying the same wrap share both ids. A new honest payment gets a new `payment_id`.

### Settlement and concurrency

- Heap `ConcurrentHashMap` is **not** used as an idempotency lock (that design poisoned retries).
- Inserting the ledger row is the claim. A crash before commit rolls back; retry can proceed.
- Accounts involved in a payment are locked with `SELECT FOR UPDATE` in **sorted VPA order** so concurrent distinct payments serialize without deadlock and without dropping a legitimate payment.
- Outcomes: `SETTLED`, `REJECTED` (insufficient funds), `DUPLICATE`, `INVALID`. The API status matches the ledger. `REJECTED` is never reported as `SETTLED`.

Language we use on purpose: **at-least-once delivery with at-most-one financial effect per authenticated `payment_id`**. Not “exactly-once processing.”

Delivery attempts are written in the settlement transaction for `SETTLED`/`REJECTED` (so they roll back with a failed ledger write). `DUPLICATE`/`INVALID` attempts are committed separately so a unique-constraint race still leaves an audit row.

### Security model

| Property | Mechanism |
|---|---|
| Confidentiality on the mesh | AES-256-GCM; AES key wrapped with RSA-OAEP (SHA-256 / MGF1 SHA-256) |
| Integrity of ciphertext | GCM tag |
| Binding of outer ids | GCM AAD = `packetId\|paymentId` |
| Sender authenticity | Ed25519 over canonical intent fields; verified against the account’s public key |
| Replay / freshness | `issuedAt` / `expiresAt` inside the signed payload, plus durable `payment_id` |
| Mutable routing | TTL, hop count, path, bridge id — **not** authenticated (they must change) |

Encryption to the **server public key** does not prove who sent the payment. That is why the sender signature exists. In this simulation `DemoService` is the device, so signing keys live in the same JVM. The check is still real: a packet that encrypts correctly but is signed by the wrong device is `INVALID`.

The compose UI does not implement UPI PIN verification. A PIN in a real UPI app is an issuer/bank factor, not something this prototype authenticates.

`/api/bridge/ingest` is open in the default local demo. Set `upi.bridge.ingest-token` (and send `X-Bridge-Token`) before exposing that endpoint publicly. That is a demo credential, not mTLS. Sender authenticity remains the Ed25519 signature. RSA/Ed25519 keys are generated at process start; packets from a previous run will not decrypt.

### Correctness lab

`POST /api/lab/run/{id}` reseeds state, runs the real pipeline, and returns PASS/FAIL:

| id | What it proves |
|---|---|
| `two_bridges_duplicate` | One ledger effect, two delivery attempts |
| `concurrent_cover_both` | ₹400 + ₹500 on ₹1000 both settle |
| `concurrent_cover_one` | ₹700 + ₹600 on ₹1000 → one SETTLED, one REJECTED, never negative |
| `tampered_packet` | Bit-flip never moves money |
| `forged_sender` | Wrong-device signature never moves money |
| `aad_tamper` | Outer `packetId` swap fails GCM AAD |
| `delayed_bridge` | In-mesh packet is unsettled until a bridge uploads |

## HTTP surface

Simulation (local demo): `/api/demo/send`, `/api/mesh/*`, `/api/lab/*`

Bridge ingest (the settlement edge): `POST /api/bridge/ingest`

Read models: `/api/accounts`, `/api/transactions`, `/api/deliveries`, `/api/payments/{id}/timeline`

Health: `/actuator/health`

Duplicate ingest returns HTTP 409; invalid packets return 400; settled/rejected return 200 with the matching `outcome`.

## Database

Default profile: H2 in-memory, Hibernate `create-drop` (zero-setup demo and **all automated tests**).

`postgres` profile: Flyway `V1__init.sql` (NUMERIC money, unique `payment_id` / `packet_hash`, FKs, `balance >= 0`). Tests are not run against Postgres in CI.

## What a larger system would add (not built)

Millions of accounts, connection pooling and contention on hot VPAs, partitioning/archival of attempts, signed bridge identity (mTLS), durable device keys, prepaid/offline-wallet authorization (the UPI Lite idea), queues between bridges and settlement workers, and operations dashboards. This repo stops at a single process plus an optional Postgres so those discussions have a concrete core.

## Known limitations

- Mesh is a five-node static graph, not BLE/Wi-Fi Direct.
- Device keys and RSA server keys are generated at process start.
- Offline double-spend across *different* `payment_id`s is allowed until settlement; the second intent may `REJECTED` if funds are gone. That is inherent to deferred authorization.
- The receiver has no cryptographic proof of funds at payment time. The dashboard will not pretend otherwise.

## Intentionally not built

Redis, Kafka, Kubernetes, ELK, Spring Security theater, multiple app replicas, fees, KYC, refunds, NPCI APIs, real Bluetooth.
