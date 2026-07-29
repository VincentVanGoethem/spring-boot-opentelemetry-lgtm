# Spring Boot + OpenTelemetry + LGTM Stack

A demo project showing how **Spring Boot 4** integrates with **OpenTelemetry** out of the box, using the **LGTM stack** (Loki · Grafana · Tempo · Mimir/Prometheus) as the observability backend — all running locally via Docker Compose.

The demo consists of two services:

- **order-service** — a web shop (The Arcane Emporium) where customers place orders. Exposes a Thymeleaf/htmx UI on port `8080`.
- **mystery-box-service** — called by the order service when a Mystery Box is purchased; uses Spring AI (OpenAI) to generate a random box of contents. Runs on port `8081`.

Both services emit **traces**, **metrics**, and **logs** via OTLP to the collector bundled inside the `grafana/otel-lgtm` container. A custom Grafana dashboard is provisioned automatically on startup.

---

## Prerequisites

- Java 25
- Maven
- Docker + Docker Compose
- An OpenAI API key (for mystery-box-service)

---

## Running the demo

### Option A — fully containerized

Build images for both services with Spring Boot's built-in [Paketo Buildpack](https://paketo.io) support (no Dockerfile needed):

```bash
./build-images.sh
```

Then start everything:

```bash
export OPENAI_API_KEY=<your-key>
docker compose up -d
```

All five containers (postgres, lgtm, mcp-grafana, mystery-box-service, order-service) start together. The shop is available at [http://localhost:8080](http://localhost:8080).

### Option B — run services locally

### 1. Start the infrastructure

```bash
docker compose up -d postgres lgtm mcp-grafana
```

This starts:

| Container | What it provides | Port |
|-----------|-----------------|------|
| `lgtm` | Grafana UI, Loki, Tempo, Prometheus, OTLP collector | `3000` (UI), `4317` (gRPC), `4318` (HTTP) |
| `postgres` | PostgreSQL for order-service | `5432` |
| `mcp-grafana` | Grafana MCP server (for AI-assisted observability) | `8888` |

### 2. Start mystery-box-service

```bash
export OPENAI_API_KEY=<your-key>
cd mystery-box-service
./mvnw spring-boot:run
```

### 3. Start order-service

```bash
cd order-service
./mvnw spring-boot:run
```

### 4. Open the shop

Navigate to [http://localhost:8080](http://localhost:8080) and place some orders. The Grafana dashboard is available at [http://localhost:3000](http://localhost:3000) (credentials: `admin` / `admin`).

---

## What to observe

### Traces
Open **Explore → Tempo** in Grafana. Each order placement produces a distributed trace spanning both services. Span attributes include business context such as `order.id`, `order.value.usd`, and `order.has.magic.box`.

### Metrics
The custom home dashboard shows business metrics emitted via Micrometer:

- `orders_created_total` — order volume by status
- `orders_value` — distribution of order values
- `orders_items_ordered_total` — units sold per SKU
- `mystery_box_generated_count_total` — mystery box generation success/error rate
- `mystery_box_items_count` — distribution of items per mystery box

### Logs
Open **Explore → Loki** and filter by `service_name`. Both services ship structured logs via the OpenTelemetry Logback appender, so logs are automatically correlated with their traces.

---

## Load generation

[`load-test.js`](./load-test.js) is a [k6](https://github.com/grafana/k6) script that drives realistic traffic through the order-service:

- Places random orders with 1–3 regular items (Rubber Duck, Forever Pen, Alarm Clock, Umbrella)
- Occasionally lists all orders to mix read and write traffic
- Ramps from 5 → 20 virtual users, holds a spike, then ramps back down

Mystery Box orders are **opt-in** (they consume OpenAI tokens). Use `MYSTERY_BOX_REQUESTS` to control how many are sent. Each request fires two calls: a valid order (`quantity: 1`) and an invalid one (`quantity: 2`) to exercise the error path.

**Run with Docker (no install needed)**

```bash
docker run --rm -i --network host grafana/k6 run - < load-test.js
```

**Run with a local k6 install**

```bash
k6 run load-test.js
```

**Point at a non-default host**

```bash
k6 run -e BASE_URL=http://my-host:8080 load-test.js
```

**Run for a fixed duration (flat load, 5 VUs) instead of the full staged scenario**

```bash
k6 run -e DURATION=1m load-test.js
```

Accepts any k6 duration string: `30s`, `2m`, `1h`, etc.

**Include Mystery Box requests**

```bash
k6 run -e MYSTERY_BOX_REQUESTS=5 load-test.js
```

---

## Leveraging the Grafana MCP server

The `mcp-grafana` container exposes a [Model Context Protocol](https://modelcontextprotocol.io) server that gives an AI assistant direct access to your Grafana instance. This enables a natural-language workflow for observability tasks — exploring data, building dashboards, and investigating incidents — without leaving the chat.

### Connect Claude Code to the MCP server

Add the following to your Claude Code MCP configuration (`.claude/settings.json` or via `claude mcp add`):

```json
{
  "mcpServers": {
    "grafana": {
      "type": "http",
      "url": "http://localhost:8888/mcp"
    }
  }
}
```

### Example prompts

**Explore data**
```
Query Prometheus for the p99 latency of the order-service over the last hour.
```
```
Show me all error logs from mystery-box-service in the last 15 minutes.
```
```
Find the slowest traces in Tempo for the createOrder operation.
```

**Build dashboards**
```
Create a Grafana dashboard that shows orders_created_total split by status,
orders_value as a heatmap, and the top 5 SKUs by units sold.
```
```
Add a panel to the existing custom dashboard showing mystery box generation
errors over time, grouped by reason.
```

**Incident investigation**
```
I'm seeing elevated error rates in order-service. Find the relevant traces,
check the logs around the same time window, and summarise what's going wrong.
```

The MCP server has read and write access to dashboards, datasources, Prometheus, Loki, and Tempo — so the assistant can go from a question to a fully provisioned dashboard in a single conversation.
