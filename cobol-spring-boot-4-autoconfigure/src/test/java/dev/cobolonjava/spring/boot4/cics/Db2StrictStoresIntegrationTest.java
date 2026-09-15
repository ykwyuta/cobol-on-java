package dev.cobolonjava.spring.boot4.cics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * STRICT の置き場と task 境界の試験を、H2 と同じ内容のまま実 Db2 で流す (暫定判断 P-143・P-144)。
 *
 * <p>{@code infra/db2/compose.yaml} の Db2 に JCC で接続する。試験ごとに使い捨ての schema に DDL を流し、終われば消す。
 * H2 で通っていた DDL、重複キーの写像 (SQLCODE -803)、行 lock による取り合い、driver-managed の native lease を、
 * Db2 の上で同じ assertion で確かめる。{@code DB2_IT_ENABLED=true} のときだけ動く。
 */
@Tag("DB2_IT")
@EnabledIfEnvironmentVariable(named = "DB2_IT_ENABLED", matches = "(?i)true")
class Db2StrictStoresIntegrationTest {

    @Nested
    class ConversationStore extends JdbcConversationStoreTest {

        @Override
        TestDatabase openDatabase() {
            return TestDatabase.db2();
        }
    }

    @Nested
    class TerminalRegistry extends JdbcTerminalRegistryTest {

        @Override
        TestDatabase openDatabase() {
            return TestDatabase.db2();
        }
    }

    @Nested
    class Starts extends JdbcCicsStartsTest {

        @Override
        TestDatabase openDatabase() {
            return TestDatabase.db2();
        }
    }

    @Nested
    class TransientData extends JdbcCicsTransientDataTest {

        @Override
        TestDatabase openDatabase() {
            return TestDatabase.db2();
        }
    }

    @Nested
    class SpringManagedBoundary extends SpringStrictTaskBoundaryTest {

        @Override
        TestDatabase openDatabase() {
            return TestDatabase.db2();
        }
    }

    @Nested
    class DriverManagedBoundary extends DriverManagedStrictTaskBoundaryTest {

        @Override
        TestDatabase openDatabase() {
            return TestDatabase.db2();
        }
    }
}
