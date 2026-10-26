package dev.autohelm.training

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.sun.net.httpserver.HttpServer
import dev.autohelm.model.ModelBundleIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.exp

class TrainingPipelineTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).addModule(JavaTimeModule()).build()
    private val io = ModelBundleIO(mapper)
    private val date = LocalDate.parse("2026-10-20")

    @Test
    fun `collects paged KIS minutes once with raw evidence and no token in files`() {
        var quoteCalls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oauth2/tokenP") { exchange ->
            val body = "{\"access_token\":\"mock-token\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice") { exchange ->
            quoteCalls++
            assertThat(exchange.requestHeaders.getFirst("tr_id")).isEqualTo("FHKST03010230")
            assertThat(exchange.requestHeaders.getFirst("authorization")).isEqualTo("Bearer mock-token")
            val firstPage = exchange.requestURI.query.contains("FID_INPUT_HOUR_1=130000")
            val range = if (firstPage) (120 downTo 1) else (0 downTo 0)
            val rows = range.map { minute ->
                val hour = 9 + minute / 60
                val m = minute % 60
                mapOf("stck_bsop_date" to "20261020", "stck_cntg_hour" to "%02d%02d00".format(hour, m),
                    "stck_oprc" to "10000", "stck_hgpr" to "10000", "stck_lwpr" to "10000",
                    "stck_prpr" to "10000", "cntg_vol" to "1")
            }
            val body = mapper.writeValueAsBytes(mapOf("rt_cd" to "0", "output2" to rows))
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val properties = TrainingProperties(command = "collect", dataDir = root,
                restUrl = URI.create("http://127.0.0.1:${server.address.port}"),
                appKey = "mock-key", appSecret = "mock-secret", symbol = "005930", venue = "KRX",
                from = date, to = date, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(13, 0))
            val collector = HistoricalDataCollector(properties, KisHistoricalClient(properties, mapper), mapper)
            assertThat(collector.collect()).isEqualTo(121)
            assertThat(collector.collect()).isZero()
            assertThat(quoteCalls).isEqualTo(2)
            val manifest = root.resolve("dev/historical/manifest/005930/KRX/regular/$date.json")
            assertThat(Files.readString(manifest)).contains("COMPLETE")
            val raw = root.resolve("dev/historical/raw/KIS/005930/KRX/$date")
            Files.list(raw).use { files ->
                val bodies = files.map(Files::readString).toList()
                assertThat(bodies).hasSize(2)
                assertThat(bodies.joinToString()).doesNotContain("mock-token", "mock-secret")
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `fits candidate and promotes identical verified bundle through dev and prd`() {
        val seoul = ZoneId.of("Asia/Seoul")
        for (day in 0L..4L) {
            val tradingDate = date.plusDays(day)
            val bars = (0..179).map { minute ->
                val price = (10_000.0 * exp(minute * 0.001)).toLong()
                HistoricalBar("dev", "005930", "KRX", "regular",
                    tradingDate.atTime(9, 0).plusMinutes(minute.toLong()).atZone(seoul).toInstant(),
                    price, price, price, price, 1, fetchedAt = Instant.parse("2026-10-26T00:00:00Z"),
                    sourceSha256 = "a".repeat(64))
            }
            val file = root.resolve("dev/historical/bars/005930/KRX/regular/$tradingDate.jsonl")
            Files.createDirectories(file.parent)
            Files.writeString(file, bars.joinToString("\n", postfix = "\n") { mapper.writeValueAsString(it) })
            val manifest = HistoricalManifest(environment = "dev", symbol = "005930", venue = "KRX",
                session = "regular", date = tradingDate, pageCount = 2, barCount = bars.size,
                barsSha256 = ModelBundleIO.sha256(Files.readAllBytes(file)), state = "COMPLETE",
                completedAt = Instant.parse("2026-10-26T00:00:00Z"))
            val path = root.resolve("dev/historical/manifest/005930/KRX/regular/$tradingDate.json")
            Files.createDirectories(path.parent)
            Files.write(path, mapper.writeValueAsBytes(manifest))
        }
        val base = TrainingProperties(command = "fit", dataDir = root, symbol = "005930", venue = "KRX",
            from = date, to = date.plusDays(4))
        val bundle = LinearModelTrainer(base, mapper, io).fit()
        assertThat(bundle.manifest.eligible).isTrue()
        assertThat(bundle.weights.heads.values).allSatisfy { head ->
            assertThat(head.validationMae).isLessThan(head.baselineMae)
        }
        val version = bundle.manifest.version
        ModelPromoter(base.copy(command = "promote", version = version, targetEnvironment = "dev"), mapper, io).promote()
        assertThat(io.loadActive(root.resolve("dev/models"))["005930:KRX:regular"]?.sha256).isEqualTo(bundle.sha256)
        assertThrows(IllegalArgumentException::class.java) {
            ModelPromoter(base.copy(command = "promote", version = version, targetEnvironment = "prd"), mapper, io).promote()
        }
        val evidence = root.resolve("approval.json")
        Files.write(evidence, mapper.writeValueAsBytes(PromotionEvidence(bundle.sha256, true, true,
            Instant.now(), "Synthetic dev replay and mock trading passed")))
        ModelPromoter(base.copy(command = "promote", version = version, targetEnvironment = "prd",
            evidenceFile = evidence), mapper, io).promote()
        assertThat(io.loadActive(root.resolve("prd/models"))["005930:KRX:regular"]?.sha256).isEqualTo(bundle.sha256)
        Files.writeString(root.resolve("prd/models/bundles/$version/weights.json"), "corrupt")
        assertThrows(IllegalArgumentException::class.java) { io.loadActive(root.resolve("prd/models")) }
    }
}
