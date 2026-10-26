package dev.autohelm.market

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Parses only the KRX/NXT trade channels from the KIS public WebSocket examples. */
class KisTickParser(private val properties: MarketProperties) {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val timeFormat = DateTimeFormatter.ofPattern("HHmmss")

    fun parse(frame: RawFrame): List<MarketTick> {
        val parts = frame.text.split('|', limit = 4)
        require(parts.size == 4 && parts[0] == "0") { "Not a plain KIS data frame" }
        val venue = when (parts[1]) {
            "H0STCNT0" -> Venue.KRX
            "H0NXCNT0" -> Venue.NXT
            else -> error("Unsupported trade channel")
        }
        val count = parts[2].toIntOrNull() ?: error("Missing trade count")
        require(count in 1..1000) { "Invalid trade count" }
        val fields = parts[3].split('^')
        // Both channel schemas currently contain 47 fields in the KIS official examples.
        require(fields.size == count * 47) { "Unexpected KIS trade field count" }
        return (0 until count).map { row ->
            val offset = row * 47
            val symbol = fields[offset]
            require(symbol in properties.symbols) { "Unsubscribed symbol" }
            val date = LocalDate.parse(fields[offset + 33], dateFormat)
            val time = LocalTime.parse(fields[offset + 1], timeFormat)
            val session = properties.sessions.singleOrNull {
                it.venue == venue && date in it.dates && !time.isBefore(it.start) && time.isBefore(it.end)
            }?.name ?: error("Unknown trading session")
            val price = fields[offset + 2].toLong()
            val volume = fields[offset + 12].toLong()
            val cumulativeVolume = fields[offset + 13].toLong()
            require(price > 0 && volume > 0 && cumulativeVolume >= 0) { "Invalid price or volume" }
            MarketTick(
                id = "${frame.id}:$row",
                environment = properties.environment,
                symbol = symbol,
                venue = venue,
                session = session,
                sourceAt = LocalDateTime.of(date, time).atZone(seoul).toInstant(),
                receivedAt = frame.receivedAt,
                price = price,
                volume = volume,
                cumulativeVolume = cumulativeVolume,
                rawFrameId = frame.id,
            )
        }
    }
}
