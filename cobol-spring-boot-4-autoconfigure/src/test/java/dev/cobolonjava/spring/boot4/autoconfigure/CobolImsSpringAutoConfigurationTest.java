package dev.cobolonjava.spring.boot4.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.rdb.JdbcDatabaseStoreProvider;
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 容器の DataSource から IMS の置き場を構成する (暫定判断 P-169、P-160)。 */
@Tag("V1")
class CobolImsSpringAutoConfigurationTest {

    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")) + "\n");

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    @AfterEach
    void forgetDataSource() {
        JdbcDatabaseStoreProvider.useDataSource(null);
        System.clearProperty(JdbcDatabaseStoreProvider.URL);
    }

    private ApplicationContextRunner runner(String databaseName) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CobolImsSpringAutoConfiguration.class,
                        DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + databaseName,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=");
    }

    @Test
    @DisplayName("Boot の AutoConfiguration imports へ登録する")
    void registersAutoConfigurationImport() throws IOException {
        Path imports = Path.of("src/main/resources/META-INF/spring",
                "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        String written = Files.readString(imports, StandardCharsets.UTF_8);
        assertTrue(written.contains(CobolImsSpringAutoConfiguration.class.getName()), written);
    }

    @Test
    @DisplayName("容器が起きると接続元が預けられ、置き場がそこから開く")
    void theStoreOpensFromTheContainerDataSource() {
        runner("ims-" + UUID.randomUUID()).run(context -> {
            assertNotNull(context.getBean(ImsDataSourceRegistrar.class));

            // ServiceLoader の差し込みが、預けた接続元から開く
            DatabaseStore store = new JdbcDatabaseStoreProvider().open(null);
            assertNotNull(store);
            try (store) {
                HierarchicalDatabase database = store.open(DBD);
                database.insert(null, DBD.root(), CodePages.DEFAULT.encode("0001"));
                store.commit(List.of(database));
            }

            try (DatabaseStore reopened = new JdbcDatabaseStoreProvider().open(null)) {
                assertEquals(1, reopened.open(DBD).roots().size());
            }
        });
    }

    @Test
    @DisplayName("容器を畳めば接続元を返し、差し込みは何も返さなくなる")
    void theDataSourceIsGivenBackWhenTheContextCloses() {
        runner("ims-" + UUID.randomUUID()).run(context -> {
            assertNotNull(new JdbcDatabaseStoreProvider().open(null));
        });
        assertNull(new JdbcDatabaseStoreProvider().open(null));
    }

    @Test
    @DisplayName("cobol.ims.spring-data-source=false なら預けない")
    void theRegistrarCanBeTurnedOff() {
        runner("ims-" + UUID.randomUUID())
                .withPropertyValues("cobol.ims.spring-data-source=false")
                .run(context -> {
                    assertTrue(context.getBeansOfType(ImsDataSourceRegistrar.class).isEmpty());
                    assertNull(new JdbcDatabaseStoreProvider().open(null));
                });
    }
}
