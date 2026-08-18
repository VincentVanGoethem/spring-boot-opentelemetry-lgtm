# Zero-code instrumentatie versus de Spring Boot OpenTelemetry starter

> Dit is de referentie voor hoe de huidige opzet werkt. Voor de migratie zelf — de acht stappen, en
> de voor- en nadelen van beide benaderingen — zie [`starter-vs-agent.md`](./starter-vs-agent.md).

Deze demo instrumenteerde beide services eerst **in de applicatie**: `spring-boot-starter-opentelemetry`
bracht de OpenTelemetry-SDK mee, `application.yaml` configureerde exporters en sampling, en twee
`OpenTelemetryConfiguration`-klassen sloten de Logback-appender en JDBC-tracing met de hand aan.

Nu gebruikt hij **zero-code instrumentatie**: een Java agent
([`opentelemetry-javaagent.jar`](https://opentelemetry.io/docs/zero-code/java/agent/)) die bij het
opstarten aan de JVM hangt. De agent herschrijft de bytecode van bekende bibliotheken terwijl die
geladen worden, dus de applicatie wordt uitgeleverd zonder OpenTelemetry-SDK, zonder exporters en
zonder observability-configuratie.

**De services hebben geen enkele `io.opentelemetry`-dependency** — niet de SDK, niet de exporters,
zelfs niet de instrumentatie-annotaties. Hun `pom.xml`-bestanden en hun Java-broncode zijn vrij van
OpenTelemetry; de poms bouwen de applicatie-jar en verder niets.

De drie lagen zijn daarmee netjes gescheiden:

| Laag | Verantwoordelijk voor | Waar |
|---|---|---|
| `pom.xml` | de applicatie-jar | — |
| `Dockerfile` | de JRE, de jar, de agent, en de JVM-argumenten die hem aanhechten en configureren | één bestand per service |
| `otel-agent.properties` | alles wat de agent doet: exporters, instrumentaties, business-spans | repository-root, gemount op `/otel` |
| `application.yaml` | de Spring-instellingen die bepalen waar de agent mee kan werken | in de jar |

Niets met `otel.*` zit in de jar. `otel-agent.properties` wordt read-only gemount op `/otel` en de
`ENTRYPOINT` van elke image wijst de agent ernaartoe met
`-Dotel.javaagent.configuration-file=/otel/otel-agent.properties`. Veranderen wat er verzameld wordt
is dus een restart — noch de jar noch de image wordt herbouwd.

Wat wél in `application.yaml` blijft staan is de Spring-helft: actuator-exposure, de Micrometer
`@Timed`-aspects, de gemeenschappelijke metertag en het log-correlatiepatroon. Dat zijn eigenschappen
van de applicatie, niet van de telemetriepijplijn, en Spring is degene die ze leest.

Dit document beschrijft wat er veranderde, wat je wint, wat je verliest, en hoe je de verloren delen
terugkrijgt waar dat uitmaakte.

---

## 1. Wat er precies veranderde

### Verwijderd

| Wat | Waar |
|---|---|
| `spring-boot-starter-opentelemetry` | beide `pom.xml` |
| `spring-boot-starter-opentelemetry-test` | beide `pom.xml` |
| `opentelemetry-logback-appender-1.0` | beide `pom.xml` |
| `opentelemetry-jdbc` | beide `pom.xml` |
| `OpenTelemetryConfiguration` (appender-installatie, `JdbcTelemetry` `BeanPostProcessor`, Spring AI-conventie) | beide services |
| `logback-spring.xml` (bestond alleen om de OTEL-appender te registreren) | beide services |
| `management.opentelemetry.tracing/logging.export.otlp.endpoint` | beide `application.yaml` |
| `management.otlp.metrics.export.step` | beide `application.yaml` |
| `management.tracing.sampling.probability` | beide `application.yaml` |
| `management.metrics.distribution.percentiles-histogram` | beide `application.yaml` |
| `spring.ai.chat.observations.*` | `mystery-box-service/application.yaml` |
| `MANAGEMENT_*`-endpointvariabelen | `docker-compose.yaml` |

### Behouden in `application.yaml`

Deze instellingen overleefden de migratie en zitten nog steeds in de jar, omdat Spring ze leest en ze
bepalen wat de agent kan oppikken:

| Instelling | Waarom nog nodig |
|---|---|
| `management.observations.annotations.enabled` | registreert Micrometer's `@Timed` / `@Counted`-aspects |
| `management.metrics.tags.application` | gemeenschappelijke tag op elke meter |
| `management.endpoints.web.exposure.include`, `management.endpoint.env.show-values` | actuator-exposure voor de demo |
| `logging.include-application-name`, `logging.pattern.correlation` | drukt de `trace_id` / `span_id` van de agent af op de console |

### Toegevoegd

| Wat | Waar |
|---|---|
| `Dockerfile` — multi-stage build, downloadt de agent en hecht hem aan met de `-javaagent`- en `-Dotel.*`-vlaggen in de `ENTRYPOINT` | beide services |
| `otel-agent.properties` — agentconfiguratie | repository-root |
| `OTEL_EXPORTER_OTLP_ENDPOINT` en de configuratiemount | `docker-compose.yaml` |
| `otel.instrumentation.methods.include` ter vervanging van `@Observed` en de `Observation`-API | `otel-agent.properties` |

### Ongewijzigd gelaten

Micrometer blijft. De `MeterRegistry`-injectie in `OrderMetricsAspect` / `MysteryBoxMetricsAspect` en
de `@Timed`-annotaties zijn onaangeroerd — de Micrometer-brug van de agent pikt die meters op en
exporteert ze over OTLP. `management.observations.annotations.enabled: true` blijft staan, want dat
is wat Micrometer's `TimedAspect` registreert.

---

## 2. Hoe de twee benaderingen zich verhouden

| | Spring Boot starter (voorheen) | Java agent (nu) |
|---|---|---|
| **Waar de SDK zit** | classpath van de applicatie | agent-jar, aangehecht aan de JVM |
| **Hoe het geconfigureerd wordt** | `application.yaml` + `@Configuration`-klassen, allebei in de jar | JVM-vlaggen / env-vars / een bestand buiten de jar |
| **Impact op de build** | 34 extra runtime-jars, waarvan 25 OpenTelemetry en tracing | nul applicatie-dependencies |
| **Wijzigen zonder rebuild** | nee — de config zit in de jar | ja — extern bestand of env-var aanpassen en herstarten |
| **Opstarttijd** | normaal | trager door bytecode-herschrijving (niet gebenchmarkt in deze repo) |
| **Artefactgrootte** | enkele MB groter | ongewijzigd (+25 MB ernaast, eenmalig) |
| **Geïnstrumenteerde bibliotheken** | wat door de Micrometer Observation-API gaat, plus wat je met de hand aansluit | [~150 bibliotheken uit de doos](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md) |
| **Spring-specifieke configuratie** | eersteklas (`management.*`) | genegeerd — de agent leest geen `management.*` |
| **Eigen spans** | `@Observed`, `Observation`-API — code plus dependency | `otel.instrumentation.methods.include` — alleen configuratie |
| **OpenTelemetry-dependencies** | de starter en zijn transitieve boom | geen enkele |
| **Werkt op niet-Spring-applicaties** | nee | ja |
| **Native image (GraalVM)** | ondersteund | **niet ondersteund** — een agent heeft een JVM nodig |
| **Versiekoppeling** | agentversie vastgeklonken aan Boot's dependency management | agent upgradet los van de applicatie |

### Wat de agent hier instrumenteert en de starter niet

- **JDBC**, zonder de `JdbcTelemetry` `BeanPostProcessor`. `otel.instrumentation.jdbc-datasource.enabled`
  en `otel.instrumentation.jdbc.experimental.transaction.enabled` in `otel-agent.properties` brengen
  de connectie- en commit/rollback-spans terug die eerst met de hand werden gebouwd.
- **Logback**, zonder appender in `logback-spring.xml`. De agent zet ook `trace_id` / `span_id` in de
  MDC — let op de underscores; Boot's eigen tracing gebruikte `traceId` / `spanId`. Daarom is
  `logging.pattern.correlation` in `application.yaml` herschreven.
- **HikariCP-poolmetrics**, **JVM-runtimemetrics** en **`http.server.active_requests`**, geen daarvan
  hoefde geconfigureerd te worden.
- **De OpenAI-call**, als een volwaardige GenAI-span — zie hieronder.
- Elke bibliotheek die de demo later toevoegt — Kafka, Redis, gRPC, AWS SDK — zonder enige
  codewijziging.

### GenAI: de ChatClient-call en zijn prompts

De agent heeft geen Spring AI-instrumentatie, maar heeft die ook niet nodig. Spring AI 2.x belt
OpenAI via de officiële `com.openai:openai-java`-client, en *die* is wel geïnstrumenteerd
(`io.opentelemetry.openai-java-1.1`). Elke `ChatClient`-call levert daardoor een span op met de naam
`chat <model>` die de GenAI-semantische conventies draagt, zonder enige configuratie:

```
gen_ai.operation.name      chat
gen_ai.provider.name       openai
gen_ai.request.model       gpt-4o-mini
gen_ai.response.model      gpt-4o-mini
gen_ai.response.id         chatcmpl-…
gen_ai.response.finish_reasons  [stop]
gen_ai.usage.input_tokens  42
gen_ai.usage.output_tokens 99
```

De prompts en completions zelf staan uit — het is gebruikersinhoud. Eén regel in
`otel-agent.properties` zet ze aan:

```properties
otel.instrumentation.genai.capture-message-content=true
```

Twee dingen daaraan gaan makkelijk mis, en beide kostten een verkeerde afslag voordat dit tegen een
draaiende agent gecontroleerd was:

1. **De inhoud staat niet op de span.** Hij komt als OTLP-*logrecords* met `event_name`
   `gen_ai.system.message`, `gen_ai.user.message`, `gen_ai.assistant.message` en `gen_ai.choice`,
   elk gestempeld met het `trace_id` en `span_id` van de `chat`-span. Wie alleen naar
   span-attributen kijkt, ziet een werkende opzet als kapot.
2. **Zonder de schakelaar worden de events tóch verstuurd**, alleen met een lege body (`{}`). Bij het
   controleren moet je dus naar de body kijken — dat het event er is, bewijst niets.

Een prompt lezen in Grafana: open de trace in Tempo, selecteer de `chat gpt-4o-mini`-span en volg de
logs-link — de records dragen het id van die span, dus je komt precies uit bij de berichten die hem
veroorzaakten. Of rechtstreeks in Loki:

```logql
{service_name="mystery-box-service"} | json | event_name=`gen_ai.user.message`
```

Wat je ziet is de prompt zoals Spring AI hem werkelijk verstuurde, inclusief de JSON-schema-instructies
die `.entity(MysteryBox.class)` eraan plakt — meestal precies wat je wilde zien als een
structured-output-call zich misdraagt.

Omdat het in het gemounte properties-bestand staat en niet in de image, is content capture weer
uitzetten een `docker compose restart` en geen rebuild — de juiste granulariteit voor een schakelaar
die bepaalt of gebruikersinhoud het proces verlaat.

### Wat de agent *niet* instrumenteert

- **Micrometer Observations als spans.** Dat is de grote — zie hieronder.

---

## 3. De enige echte gedragsbreuk: `@Observed` levert geen spans meer op

Micrometer's `@Observed` en de `Observation`-API leveren alleen een span op als er een tracer in de
`ObservationRegistry` gebrugd is. De Spring Boot starter deed precies dat. De agent niet: die brugt
Micrometer-**metrics** naar OpenTelemetry, maar niets brugt Micrometer-**Observations** naar de
OpenTelemetry-tracer.

Ongewijzigd gelaten zou `@Observed(name = "order.service")` stilletjes zijn gedegradeerd tot een
timer zonder span — de trace zou nog steeds HTTP en JDBC tonen, maar elke business-span daartussen
zou verdwenen zijn.

De voor de hand liggende vervanger is de `@WithSpan`-annotatie, maar die vereist
`opentelemetry-instrumentation-annotations` op het classpath. Omdat het doel hier een module is met
**helemaal geen OpenTelemetry-dependency**, worden de spans in plaats daarvan gedeclareerd in
`otel-agent.properties`:

```properties
otel.instrumentation.methods.include=\
  com.example.orderservice.OrderService[createOrder,getOrders,processItems,processItem,createMysteryBox];\
  com.example.mysterybox.MysteryBoxService[generateMysteryBox,fetchMysteryBox]
```

| Voorheen (Micrometer, in code) | Nu (agent, in configuratie) |
|---|---|
| `@Observed(name = "order.service")` op de klasse | één regel per methode in `otel.instrumentation.methods.include` |
| `Observation.createNotStarted(...).observe(...)` rond een blok | het blok naar een private methode halen en die opnemen |
| span met de naam `order.service` | span met de naam `OrderService.createOrder` |
| `.lowCardinalityKeyValue(k, v)` (span **én** metrictag) | niet beschikbaar — gebruik `MeterRegistry` voor de metriek |
| `.highCardinalityKeyValue(k, v)` (alleen span) | niet beschikbaar — zet het in een logregel |

Drie consequenties die het waard zijn om te kennen:

**Het werkt waar Spring AOP dat niet doet.** `@Observed` en `@Timed` lopen via een Spring-proxy en
worden dus stilzwijgend genegeerd op private methodes en bij self-invocation. De agent weeft
bytecode, dus `OrderService.processItems` en `processItem` zijn private, worden vanuit dezelfde bean
aangeroepen, en leveren toch spans op.

**Geen span-attributen.** Dat is de prijs van het schrappen van de annotatie-dependency:
`otel.instrumentation.methods.include` legt entry, exit, duur en exceptions vast, maar kan geen
methode-argumenten of returnwaarden tot span-attribuut maken zoals `@SpanAttribute` en
`Span.current().setAttribute()` dat kunnen. De detail die vroeger op de span stond — de SKU, het
aantal items, de GenAI-prompt — gaat nu de logregels in. De agent stempelt `trace_id` / `span_id` op
elk record, dus Grafana loopt nog steeds van de span naar precies die logs; je kunt er alleen niet
meer op filteren of aggregeren in TraceQL.

**Methodenamen worden een interface.** Het properties-bestand verwijst naar klassen en methodes bij
naam. Een hernoeming of een geïnlinede methode laat de span geruisloos verdwijnen — er is geen
compile-time controle zoals een annotatie die geeft. Is die ruil voor jouw codebase niet de moeite,
voeg dan `opentelemetry-instrumentation-annotations` toe (alleen annotaties, geen SDK, geen
exporters, ~30 KB) en gebruik `@WithSpan` / `@SpanAttribute`; al het overige in dit document blijft
hetzelfde.

---

## 4. Metriek-namen zijn veranderd

De agent emit de OpenTelemetry HTTP-semantische conventies; Boot's Micrometer-instrumentatie emitte
zijn eigen. `grafana/custom-dashboard.json` is daarop aangepast:

| Voorheen | Nu |
|---|---|
| `http_server_request_duration_milliseconds_bucket` | `http_server_request_duration_seconds_bucket` |
| `http_server_request_duration_active_milliseconds_count` | `http_server_active_requests` |

Let op de **eenheidswijziging van milliseconden naar seconden**, en dat de Micrometer-brug van de
agent seconden als basistijdseenheid gebruikt — de `@Timed`-histogram heet dus nu
`mystery_boxes_generation_time_seconds_bucket`.

De business-metrics die via `MeterRegistry` geregistreerd worden (`orders_created_total`,
`orders_value_*`, `orders_items_ordered_total`, `mystery_box_generated_count_total`,
`mystery_box_items_count_*`) behouden hun naam — de brug geeft de Micrometer-naam ongewijzigd door.

**Bekende dubbeling:** Spring Boot Actuator registreert nog steeds zijn eigen
`http.server.requests`-timer, die de brug nu óók exporteert, naast de `http.server.request.duration`
van de agent. Allebei meten hetzelfde onder een andere naam. Zet er één uit als de dubbeling stoort;
het dashboard gebruikt die van de agent.

---

## 5. Configuratievolgorde

Twee onafhankelijke ketens, omdat twee verschillende componenten de configuratie lezen.

### Spring

Spring lost properties op in de gebruikelijke volgorde (hoogste wint): command-line-argumenten, dan
systeem-properties (`-D…`), dan omgevingsvariabelen, dan `application.yaml` in de jar. Er wordt niets
van buitenaf overheen gelegd, dus de instellingen in `application.yaml` zijn de basislijn en elke
`-D` of `SPRING_*`/`MANAGEMENT_*`-variabele overschrijft ze.

### De agent (`otel-agent.properties`)

De agent lost configuratie op in deze volgorde (hoogste wint):

1. Systeem-properties (`-Dotel.…`)
2. Omgevingsvariabelen (`OTEL_…`)
3. Het properties-bestand (`otel.javaagent.configuration-file`)
4. `AutoConfigurationCustomizerProvider`-SPI

De naam van de omgevingsvariabele is de systeem-property in hoofdletters met `.` en `-` vervangen
door `_`: `otel.instrumentation.micrometer.enabled` → `OTEL_INSTRUMENTATION_MICROMETER_ENABLED`.

Daarom zet `otel-agent.properties` het OTLP-endpoint bewust **niet**: waar telemetrie heen gaat is
een eigenschap van de deployment, dus dat komt binnen als
`OTEL_EXPORTER_OTLP_ENDPOINT=http://lgtm:4318` vanuit `docker-compose.yaml`.

**Valkuil 1 — `otel.service.name` en de vorm van de YAML.** De agent kan de servicenaam zelf
afleiden, maar doet dat door `application.yaml` *zelf* te parsen, met een gewone YAML-parser, langs
`spring` → `application` → `name`. De samengestelde sleutelvorm die Spring Boot ook accepteert —
`spring.application.name: order-service` — parset naar `{spring: {"application.name": ...}}` en de
lookup mist hem, waardoor `service.name` op `unknown_service:java` blijft staan.

Twee consequenties. Ten eerste gebruiken beide `application.yaml`-bestanden de volledig geneste vorm,
zodat de fallback werkt. Ten tweede leest die fallback een bestand *in de jar*, wat precies is wat
deze opzet vermijdt — dus `otel.service.name` wordt expliciet gezet in de `ENTRYPOINT` van elke
image, en de telemetrie-identiteit komt net als al het andere van buiten het artefact.

**Valkuil 2 — `otel.semconv-stability.opt-in`.** Die wordt gelezen *voordat* het configuratiebestand
geladen is, dus hij werkt alleen als systeem-property of omgevingsvariabele — vandaar
`-Dotel.semconv-stability.opt-in=database` in elke `ENTRYPOINT`. Zonder deze vlag dragen JDBC-spans
de verouderde `db.statement` / `db.system`-attributen in plaats van het stabiele `db.query.text`.

### Defaults waarop geleund wordt in plaats van geconfigureerd

| Property | Default | Vervangt |
|---|---|---|
| `otel.traces.exporter` / `otel.metrics.exporter` / `otel.logs.exporter` | `otlp` | `management.opentelemetry.*.export.otlp.*` |
| `otel.exporter.otlp.protocol` | `http/protobuf` | — |
| `otel.exporter.otlp.endpoint` | `http://localhost:4318` | de drie `/v1/...`-endpoints |
| `otel.traces.sampler` | `parentbased_always_on` | `management.tracing.sampling.probability: 1.0` |
| `otel.service.name` | zou terugvallen op `spring.application.name` via de Spring Boot resource provider van de agent — wordt in plaats daarvan expliciet gezet bij het opstarten | `spring.application.name` |

---

## 6. Draaien

Alles hieronder is verpakking, geen applicatiecode. De poms bouwen de applicatie-jar; de Dockerfiles
bepalen wat hem draait en hoe.

### De agent binnenhalen

Er is geen handmatige downloadstap en geen Maven-betrokkenheid. De runtime-stage van elke Dockerfile
haalt de agent rechtstreeks van Maven Central:

```dockerfile
ARG OTEL_AGENT_VERSION=2.30.0
RUN mkdir -p /opt/otel
ADD --chmod=644 \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
    /opt/otel/opentelemetry-javaagent.jar
```

`ARG OTEL_AGENT_VERSION` is de enige plek waar de versie gepind staat, en
`docker build --build-arg OTEL_AGENT_VERSION=…` volstaat om een andere te proberen.

`mkdir -p /opt/otel` vóór de `ADD` is niet cosmetisch: `ADD --chmod` past de modus ook toe op mappen
die hij impliciet aanmaakt, waardoor `/opt/otel` op `644` zou blijven staan. Zonder het execute-bit
kan de non-root-gebruiker er niet in en faalt de JVM bij het openen van de agent.

Omdat dit een Docker-instructie is en geen Maven-dependency, komt de agent nooit op het compile- of
runtime-classpath — `mvn dependency:list` meldt geen enkel `io.opentelemetry`-artefact, en dat is de
eigenschap waar deze hele opzet omheen gebouwd is.

### De images bouwen

Elke service heeft zijn eigen multi-stage `Dockerfile`; het project gebruikt geen
`spring-boot:build-image` of Paketo-buildpacks, dus hoe de image in elkaar gezet wordt is zichtbaar
in een bestand in plaats van in plugin-configuratie:

```dockerfile
FROM eclipse-temurin:25-jdk AS build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline     # dependencies in een eigen cachebare laag
COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:25-jre
... agent-ADD zoals hierboven ...
COPY --from=build /build/target/order-service-*.jar /app/application.jar
USER 1001
ENTRYPOINT ["java", \
  "-javaagent:/opt/otel/opentelemetry-javaagent.jar", \
  "-Dotel.javaagent.configuration-file=/otel/otel-agent.properties", \
  "-Dotel.service.name=order-service", \
  "-Dotel.semconv-stability.opt-in=database", \
  "-jar", "/app/application.jar"]
```

De build-stage draait Maven zelf, dus `docker build` werkt vanaf een schone checkout zonder lokale
JDK.

### Wat de image vastlegt, en wat hij openlaat

Alles in de `ENTRYPOINT` is een eigenschap van *de image*: welke agent aangehecht wordt, waar zijn
configuratie gemount zal worden, en welke service dit is. Dat ligt eenmalig vast bij het bouwen, in
plaats van herhaald te worden in elke deployment-beschrijving. De poms bevatten er niets van — geen
agent, geen JVM-argumenten, geen observability-configuratie.

Bewust weggelaten: `otel.exporter.otlp.endpoint`. Waar telemetrie heen gestuurd wordt verschilt per
omgeving, dus dat blijft een `OTEL_EXPORTER_OTLP_ENDPOINT`-variabele in `docker-compose.yaml`. Die
scheiding doet er meer toe dan het lijkt, want **bij de agent wint een `-D` van een
`OTEL_*`-variabele** — alles wat in de `ENTRYPOINT` gebakken zit, is daarna niet meer tijdens het
draaien te overschrijven. Bak wat waar is over de image; laat wat waar is over de deployment aan de
omgeving.

Let ook op waar de grens ligt voor de agent als geheel: **de agent zit in de image, zijn configuratie
niet.** De agent is onderdeel van de runtime — dezelfde categorie als de JRE — dus hem inbakken maakt
de image zelfstandig. `otel-agent.properties` blijft erbuiten en wordt gemount op `/otel`, zodat wat
er verzameld wordt nog steeds met een restart verandert in plaats van met een rebuild.

### Starten

```bash
./build-images.sh
export OPENAI_API_KEY=<jouw-key>
docker compose up -d
```

**Nagaan waar een property vandaan komt** — `management.endpoints.web.exposure.include` staat open,
dus `/actuator/env/<property>` meldt de winnende bron:

```bash
curl -s localhost:8080/actuator/env/management.observations.annotations.enabled
# "source": "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'"
```

**Controleren of de agent aangehecht is** — voeg `OTEL_JAVAAGENT_DEBUG=true` toe aan de service in
`docker-compose.yaml` en zoek naar `opentelemetry-javaagent - version: 2.30.0` in de eerste regels
van `docker compose logs order-service`.

### Alternatieve verpakking

Wie buildpacks verkiest boven een Dockerfile: de
[Paketo OpenTelemetry buildpack](https://github.com/paketo-buildpacks/opentelemetry) levert de agent
aan tijdens het bouwen met `BP_OPENTELEMETRY_ENABLED=true` en regelt `JAVA_TOOL_OPTIONS` voor je. Het
addertje is dat hij zijn eigen agentversie kiest en start vanaf `OTEL_JAVAAGENT_ENABLED=false`,
`OTEL_LOGS_EXPORTER=none`, `OTEL_METRICS_EXPORTER=none`, dus je ruilt de expliciete versiepin in voor
een paar runtime-overrides. Deze demo gebruikt een Dockerfile zodat de agentversie en de
JVM-argumenten die hem configureren in één bestand zichtbaar zijn.

---

## 7. Wanneer welk

**Gebruik de Java agent als** je telemetrie wilt van applicaties die je niet wilt (of kunt)
aanpassen, je een heterogene vloot draait waar één mechanisme alles moet dekken, je instrumentatie
wilt upgraden zonder te herbouwen, of je dekking nodig hebt van bibliotheken die niet via Micrometer
lopen.

**Gebruik de Spring Boot starter als** je naar een GraalVM native image compileert, je geen agent
mág aanhechten (sommige managed platforms en beveiligingsregels verbieden het), je
`management.*`-configuratie en Spring Boot's testondersteuning wilt
(`spring-boot-starter-opentelemetry-test`), of je instrumentatie vooral op Micrometer
`@Observed`/`Observation` gebaseerd is en je die spans gratis wilt.

**Ze sluiten elkaar niet uit**, maar combineren vraagt zorg: draai je beide, dan krijg je dubbele
HTTP-spans en dubbele metrics tenzij je één kant expliciet uitzet.

---

## Referenties

- [Zero-code instrumentatie — Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
- [Agentconfiguratie](https://opentelemetry.io/docs/zero-code/java/agent/configuration/)
- [Spans op methodeniveau: `@WithSpan`, `@SpanAttribute` en `otel.instrumentation.methods.include`](https://opentelemetry.io/docs/zero-code/java/agent/annotations/)
- [Ondersteunde bibliotheken en hun configuratievlaggen](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md)
- [SDK-configuratiereferentie](https://opentelemetry.io/docs/languages/java/configuration/)
