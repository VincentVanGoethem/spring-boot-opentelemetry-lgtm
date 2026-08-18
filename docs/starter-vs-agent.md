# Van starter naar agent

Een stap-voor-stap verslag van wat er veranderde toen de OpenTelemetry-instrumentatie uit de
applicatie verhuisde naar de JVM — eerst het denkmodel, dan de acht concrete stappen, dan wat het
opleverde en wat het kostte.

Voor de referentiedocumentatie over hoe de huidige opzet werkt, zie
[`zero-code-instrumentation.md`](./zero-code-instrumentation.md). Dit document is het
migratieverhaal en de afweging.

---

## 1. Twee denkmodellen

Beide opzetten leveren traces, metrics en logs via OTLP aan dezelfde LGTM-stack. Het verschil zit
niet in wat eruit komt, maar in **wie de instrumentatie schrijft en waar die woont**.

**Starter — de applicatie meet zichzelf.** `spring-boot-starter-opentelemetry` zet de
OpenTelemetry-SDK op het classpath. Spring Boot configureert de exporters vanuit `application.yaml`,
en Micrometer's Observation-API is het kanaal: alles wat daar doorheen gaat — HTTP, `@Observed`,
`@Timed` — wordt een span of een meter. Wat er niet doorheen gaat, bestaat niet. Vandaar dat JDBC en
Logback met de hand aangesloten moesten worden in een `@Configuration`-klasse.

**Agent — de JVM meet de applicatie.** Een `-javaagent` hangt bij het opstarten aan de JVM en
herschrijft de bytecode van bibliotheken terwijl die geladen worden. De applicatie weet van niets:
geen SDK, geen exporter, geen import. Herkent de agent een bibliotheek, dan is die geïnstrumenteerd —
of jij eraan gedacht had of niet. Herkent hij hem niet, dan helpt geen enkele annotatie in je code.

Dat is de hele afweging in één zin: **je ruilt precisie in voor dekking.**

| Laag | Starter | Agent |
|---|---|---|
| Waar de SDK zit | op het classpath, in de jar | in de agent-jar, naast de JVM |
| Wie instrumenteert | Micrometer Observation + handwerk | bytecode-weaving, ~150 bibliotheken |
| Waar de config staat | `application.yaml`, in de jar | `otel-agent.properties`, gemount |
| Config wijzigen | rebuild + redeploy | restart |
| Versiekoppeling | vastgeklonken aan de Boot-versie | los van de applicatie te upgraden |
| Reikwijdte | deze Spring-applicatie | elke JVM, Spring of niet |

---

## 2. De acht stappen

De volgorde hieronder is de volgorde waarin de migratie ook echt liep: eerst het gewicht eruit, dan
de vervangers erin, dan de scherven opruimen.

### Stap 1 — Dependencies eruit

Vier artefacten verdwenen uit beide poms. Daarmee ging de hele OpenTelemetry-SDK mee, de
OTLP-exporters en de micrometer-tracing-brug: **112 → 78 runtime-jars**.

```
spring-boot-starter-opentelemetry
spring-boot-starter-opentelemetry-test
opentelemetry-logback-appender-1.0
opentelemetry-jdbc
```

De agent komt in plaats daarvan binnen als Docker-instructie:

```dockerfile
ARG OTEL_AGENT_VERSION=2.30.0
RUN mkdir -p /opt/otel
ADD --chmod=644 \
  https://repo1.maven.org/.../opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
  /opt/otel/opentelemetry-javaagent.jar
```

Het is een Docker-instructie, geen dependency: `mvn dependency:list` meldt geen enkel
`io.opentelemetry`-artefact meer. De poms bouwen de applicatie-jar en verder niets.

### Stap 2 — Exporters en sampling

Alle `management.opentelemetry.*`-, `management.otlp.*`- en `management.tracing.*`-sleutels
verdwenen. De agent heeft er defaults voor die precies waren wat deze demo wilde.

Voorheen:

```yaml
management:
  tracing.sampling.probability: 1.0
  otlp.metrics.export.step: 5s
  opentelemetry:
    tracing.export.otlp.endpoint: http://localhost:4318/v1/traces
    logging.export.otlp.endpoint: http://localhost:4318/v1/logs
```

Nu — niets, want dit zijn de defaults van de agent:

```
otel.traces.exporter / otel.metrics.exporter / otel.logs.exporter = otlp
otel.exporter.otlp.protocol = http/protobuf
otel.traces.sampler = parentbased_always_on
```

Alleen het endpoint bleef over, en dat staat in `docker-compose.yaml` — waar telemetrie heen gaat
verschilt per omgeving, dus dat is geen eigenschap van de image.

### Stap 3 — Logging

De starter had een appender in `logback-spring.xml` nodig plus een bean die hem bij de SDK aanmeldde:

```xml
<appender name="OTEL" class="io...OpenTelemetryAppender"/>
```

```java
@Bean
InitializingBean installOpenTelemetryAppender(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
}
```

Beide zijn verwijderd. De agent instrumenteert Logback zelf, stuurt elk record over OTLP en zet de
trace-ids in de MDC.

> **Let op.** De MDC-sleutels veranderen van `traceId`/`spanId` naar `trace_id`/`span_id`. Wie een
> `logging.pattern.correlation` heeft staan, ziet die stilletjes leeg worden.

### Stap 4 — JDBC

Hier is het verschil in reikwijdte het duidelijkst. De starter zag geen SQL, dus de `DataSource` werd
ingepakt door een `BeanPostProcessor`:

```java
if (bean instanceof DataSource ds && !(bean instanceof OpenTelemetryDataSource)) {
    return JdbcTelemetry.builder(openTelemetry)
            .setDataSourceInstrumenterEnabled(true)
            .setTransactionInstrumenterEnabled(true)
            .build().wrap(ds);
}
```

Bij de agent zijn het twee regels configuratie:

```properties
otel.instrumentation.jdbc-datasource.enabled=true
otel.instrumentation.jdbc.experimental.transaction.enabled=true
```

En er kwamen spans bij die niemand had aangevraagd: `OrderRepository.save` uit de Spring
Data-instrumentatie, plus HikariCP-poolmetrics.

### Stap 5 — Business-spans

De enige echte gedragsbreuk. Micrometer's `@Observed` levert alleen een span op als er een tracer in
de `ObservationRegistry` gebrugd is. De agent brugt Micrometer-*metrics*, maar geen Observations.
Ongewijzigd gelaten zou `@Observed` stilletjes zijn spans hebben verloren.

Voorheen, in de code:

```java
@Observed(name = "order.service")
class OrderService {

    Observation.createNotStarted("order.item.process", observationRegistry)
            .lowCardinalityKeyValue("sku", item.sku())
            .highCardinalityKeyValue("quantity", String.valueOf(item.quantity()))
            .observe(() -> ...);
}
```

Nu, in de configuratie:

```properties
otel.instrumentation.methods.include=\
  com.example.orderservice.OrderService[createOrder,getOrders,processItems,processItem,createMysteryBox];\
  com.example.mysterybox.MysteryBoxService[generateMysteryBox,fetchMysteryBox]
```

De winst: bytecode-weaving trekt zich niets aan van Spring AOP. `processItems` en `processItem` zijn
**private** en worden vanuit dezelfde bean aangeroepen — met `@Observed` was daar nooit een span van
gekomen.

> **De prijs.** Method-instrumentatie kan geen argumenten of returnwaarden als span-attribuut
> vastleggen. `order.item.sku` bestaat niet meer als attribuut; die detail staat nu in de logregel,
> en je kunt er niet meer op filteren of aggregeren in TraceQL. De configuratie is bovendien aan
> methodenamen gekoppeld: hernoem er één en de span verdwijnt geruisloos, zonder compile-time
> waarschuwing.

### Stap 6 — Metrics

Hier veranderde de code niets. De `MeterRegistry`-aspects en de `@Timed`-annotaties bleven staan; de
Micrometer-brug van de agent exporteert ze. Wat wél veranderde zijn de namen en eenheden van de
HTTP-metrics, en dat brak het Grafana-dashboard.

| Starter | Agent |
|---|---|
| `http_server_request_duration_milliseconds_bucket` | `http_server_request_duration_seconds_bucket` |
| `http_server_request_duration_active_milliseconds_count` | `http_server_active_requests` |

> **Dubbeling.** Spring Boot Actuator registreert nog steeds zijn eigen `http.server.requests`-timer,
> die de brug óók exporteert — naast de `http.server.request.duration` van de agent. Twee metrieken
> voor hetzelfde, onder verschillende namen. Het dashboard gebruikt die van de agent.

### Stap 7 — GenAI-prompts

Onverwachte winst. `spring.ai.chat.observations.log-prompt` leverde zonder tracer-brug niets op. Maar
Spring AI 2.x belt OpenAI via de officiële `com.openai:openai-java`-client, en díé instrumenteert de
agent wel.

Zonder enige configuratie:

```
chat gpt-4o-mini
  gen_ai.request.model            gpt-4o-mini
  gen_ai.response.finish_reasons  [stop]
  gen_ai.usage.input_tokens       42
  gen_ai.usage.output_tokens      99
```

De prompts zelf staan uit — het is gebruikersinhoud. Eén regel zet ze aan:

```properties
otel.instrumentation.genai.capture-message-content=true
```

Ze komen niet op de span terecht, maar als logrecords met het span-id van de `chat`-span, dus je
klikt vanaf die span door naar de exacte prompt.

### Stap 8 — Aanhechten

Wat overblijft is opstartwerk, en dat staat in de `ENTRYPOINT` — niet in de pom, niet in de jar.

```dockerfile
ENTRYPOINT ["java", \
  "-javaagent:/opt/otel/opentelemetry-javaagent.jar", \
  "-Dotel.javaagent.configuration-file=/otel/otel-agent.properties", \
  "-Dotel.service.name=order-service", \
  "-Dotel.semconv-stability.opt-in=database", \
  "-jar", "/app/application.jar"]
```

> **Voetangel.** Bij de agent wint een `-D` van een `OTEL_*`-variabele, dus alles wat je in de image
> bakt is daarna niet meer vanuit de omgeving te overschrijven. En sommige sleutels —
> `otel.semconv-stability.opt-in` — worden gelezen vóór het configuratiebestand geladen is, dus die
> wérken *alleen* als `-D` of omgevingsvariabele.

---

## 3. Voor- en nadelen

### Agent — zero-code instrumentatie

**Voordelen**

- Het artefact weet niets van telemetrie. Geen dependency, geen import, geen versieconflict.
- Dekking die je niet hoefde te bedenken: JDBC, Spring Data, HikariCP, JVM-runtime, GenAI.
- Configuratie buiten het artefact: wijzigen is een restart, geen rebuild.
- Bytecode-weaving verslaat Spring AOP — ook private methodes en self-invocation.
- Eén mechanisme voor een hele vloot, ook voor niet-Spring-applicaties.
- Instrumentatie upgraden zonder de applicatie te herbouwen.

**Nadelen**

- Geen GraalVM native image — een agent heeft een JVM nodig.
- Geen span-attributen uit method-instrumentatie. Business-context verhuist naar logregels en is niet
  meer aggregeerbaar in TraceQL.
- De configuratie verwijst naar klasse- en methodenamen: een hernoeming breekt spans zonder
  waarschuwing.
- `@Observed` en de Observation-API leveren geen spans meer op.
- Metriek-namen en -eenheden veranderen; dashboards moeten mee.
- Inconsistente configuratie: sommige sleutels werken alleen als `-D`, en een `-D` is niet te
  overschrijven.
- Trager opstarten door bytecode-herschrijving, en een ondoorzichtige binary van 25 MB in de runtime.

### Starter — instrumentatie in de applicatie

**Voordelen**

- Werkt in een GraalVM native image.
- Configuratie onder `management.*`, met Spring-profielen, relaxed binding en IDE-aanvulling.
- `@Observed` geeft span én meter uit één annotatie, met controle over cardinaliteit.
- Span-attributen precies waar je ze wilt, met compile-time controle.
- Testondersteuning via `spring-boot-starter-opentelemetry-test`.
- Geen agent aan te hechten — sommige platforms en beveiligingsregels verbieden dat.

**Nadelen**

- 25 extra jars in het artefact, vastgeklonken aan de Boot-versie.
- Dekt alleen wat door de Observation-API gaat; de rest is handwerk.
- JDBC-spans en de log-appender moesten met eigen `@Configuration`-code aangesloten worden.
- Configuratie zit in de jar: wijzigen betekent herbouwen.
- Alleen bruikbaar voor deze Spring-applicatie, niet vloot-breed.

---

## 4. Wanneer welk

**Kies de agent als** je applicaties wilt meten die je niet kunt of wilt aanpassen, je een gemengde
vloot hebt waar één mechanisme alles moet dekken, je instrumentatie wilt upgraden zonder te
herbouwen, of je dekking nodig hebt van bibliotheken die niet via Micrometer lopen.

**Kies de starter als** je naar een GraalVM native image compileert, je geen agent mág aanhechten, je
instrumentatie vooral `@Observed`-gebaseerd is en je die spans gratis wilt, of je rijke
span-attributen wilt met compile-time controle.

Ze sluiten elkaar niet uit, maar samen draaien levert dubbele HTTP-spans en dubbele metrics op,
tenzij je één kant expliciet uitzet.

---

## 5. Cijfers uit deze repository

| | |
|---|---|
| 112 → 78 | runtime-jars op het classpath van order-service |
| 25 | OpenTelemetry- en tracing-jars die verdwenen |
| 106 | regels instrumentatiecode verwijderd uit de twee configuratieklassen |
| 0 | `io.opentelemetry`-artefacten over in `dependency:list` |

Elke span, metriek en logregel in dit document is gecontroleerd tegen de draaiende LGTM-stack, niet
overgenomen uit documentatie. De GenAI-prompts zijn geverifieerd tegen een OpenAI-compatibele
stubserver, omdat er geen API-sleutel beschikbaar was.

Eén ding is **niet** gemeten: het verschil in opstarttijd tussen beide opzetten. De agent besteedt
aantoonbaar tijd aan het herschrijven van bytecode, maar er is geen schone starter-baseline om tegen
af te zetten.
