package dev.autohelm.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class StockCatalogTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `search returns ranked company and its DART code`() {
        val path = directory.resolve("dev/control/catalog.sqlite")
        java.nio.file.Files.createDirectories(path.parent)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE TABLE catalog_meta(id INTEGER, market_date TEXT)")
                statement.execute("INSERT INTO catalog_meta VALUES(1,'20261023')")
                statement.execute("CREATE TABLE company(stock_code TEXT,name TEXT,corp_code TEXT,market TEXT,market_cap INTEGER,rank INTEGER)")
                statement.execute("INSERT INTO company VALUES('005930','삼성전자','00126380','KOSPI',100,1)")
                statement.execute("INSERT INTO company VALUES('000660','SK하이닉스','00164779','KOSPI',80,2)")
            }
        }
        val catalog = StockCatalog(ControlProperties(dataDir = directory))
        assertThat(catalog.search("삼성").companies).hasSize(1)
            .first().extracting("dartCorpCode").isEqualTo("00126380")
        assertThat(catalog.search("0059").companies.single().symbol).isEqualTo("005930")
        assertThat(catalog.search("%").companies).isEmpty()
        assertThat(catalog.search("").total).isEqualTo(2)
    }
}
