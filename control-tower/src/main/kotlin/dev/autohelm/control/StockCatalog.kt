package dev.autohelm.control

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.sql.DriverManager

data class CatalogCompany(val symbol: String, val name: String, val dartCorpCode: String,
                          val market: String, val marketCap: Long, val rank: Int)
data class CatalogResult(val marketDate: String?, val total: Int, val companies: List<CatalogCompany>)

@Component
class StockCatalog(private val properties: ControlProperties) {
    fun search(query: String, limit: Int = 20): CatalogResult {
        val path = properties.dataDir.resolve(properties.environment).resolve("control/catalog.sqlite")
        if (!Files.isRegularFile(path)) return CatalogResult(null, 0, emptyList())
        val normalized = query.trim().take(80)
        DriverManager.getConnection("jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro").use { db ->
            val date = db.createStatement().use { statement ->
                statement.executeQuery("SELECT market_date FROM catalog_meta WHERE id=1").use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
            val total = db.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM company").use { rows -> rows.next(); rows.getInt(1) }
            }
            val sql = "SELECT stock_code,name,corp_code,market,market_cap,rank FROM company " +
                "WHERE (?='' OR name LIKE ? ESCAPE '\\' OR stock_code LIKE ? ESCAPE '\\') ORDER BY rank LIMIT ?"
            val pattern = "%" + normalized.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%"
            val companies = db.prepareStatement(sql).use { statement ->
                statement.setString(1, normalized)
                statement.setString(2, pattern)
                statement.setString(3, pattern)
                statement.setInt(4, limit.coerceIn(1, 100))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(CatalogCompany(rows.getString(1), rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getLong(5), rows.getInt(6)))
                    }
                }
            }
            return CatalogResult(date, total, companies)
        }
    }
}
