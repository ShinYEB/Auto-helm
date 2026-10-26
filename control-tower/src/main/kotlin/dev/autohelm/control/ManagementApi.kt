package dev.autohelm.control

import dev.autohelm.control.contract.WatchlistSnapshot
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ManagementApi(private val watchlist: WatchlistStore, private val data: ConsoleData,
                    private val catalog: StockCatalog, private val details: SymbolDetails) {
    @GetMapping("/catalog")
    fun catalog(@RequestParam(defaultValue = "") q: String): CatalogResult = catalog.search(q)

    @GetMapping("/symbols/{symbol}/details")
    fun details(@PathVariable symbol: String): SymbolDetailsSnapshot = details.snapshot(symbol)
    @GetMapping("/symbols")
    fun symbols(): WatchlistSnapshot = watchlist.current()

    @PutMapping("/symbols/{symbol}")
    fun putSymbol(@PathVariable symbol: String, @RequestBody input: SymbolInput): WatchlistSnapshot =
        watchlist.put(symbol, input)

    @DeleteMapping("/symbols/{symbol}")
    fun disableSymbol(@PathVariable symbol: String, @RequestParam revision: Long): WatchlistSnapshot =
        watchlist.disable(symbol, revision)

    @GetMapping("/models")
    fun models(): ModelOverview = data.models()

    @GetMapping("/information")
    fun information(): InformationOverview = data.information()

    @ExceptionHandler(RevisionConflict::class)
    fun conflict(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "종목 목록이 변경되었습니다. 새로고침 후 다시 시도하세요."))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "Invalid request")))
}
