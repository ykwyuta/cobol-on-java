package dev.cobolonjava.compiler.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.semantic.SqlBlockParser.CursorDeclaration;
import dev.cobolonjava.compiler.semantic.SqlBlockParser.Executable;
import dev.cobolonjava.compiler.semantic.SqlBlockParser.HostRef;
import dev.cobolonjava.db2.SqlOperation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** EXEC SQL の分け方。 */
@Tag("V1")
class SqlBlockParserTest {

    private static HostRef host(String name) {
        return new HostRef(name, null);
    }

    @Test
    @DisplayName("SELECT INTO は INTO 句を出力へ分け、host variable を ? にし、定数の中の : は触らない")
    void splitsSelectInto() {
        Executable select = assertInstanceOf(Executable.class, SqlBlockParser.parse("""
                EXEC SQL
                   SELECT ACCOUNT_NAME,  ACCOUNT_TYPE
                   INTO  :HV-NAME,
                         :HV-TYPE
                   FROM ACCOUNT
                   WHERE (ACCOUNT_NUMBER = :HV-NUMBER AND NOTE = 'a:b')
                END-EXEC"""));

        assertEquals(SqlOperation.SELECT_ONE, select.operation());
        assertEquals("SELECT ACCOUNT_NAME, ACCOUNT_TYPE FROM ACCOUNT"
                + " WHERE (ACCOUNT_NUMBER = ? AND NOTE = 'a:b')", select.sql());
        assertEquals(List.of(host("HV-NAME"), host("HV-TYPE")), select.outputs());
        assertEquals(List.of(host("HV-NUMBER")), select.inputs());
    }

    @Test
    @DisplayName("INSERT / UPDATE / DELETE は host variable を入力として順に集める")
    void collectsInputs() {
        Executable insert = assertInstanceOf(Executable.class, SqlBlockParser.parse(
                "EXEC SQL INSERT INTO T (A, B) VALUES(:HV-A, :HV-B:HV-B-IND) END-EXEC"));
        assertEquals("INSERT INTO T (A, B) VALUES(?, ?)", insert.sql());
        assertEquals(List.of(host("HV-A"), new HostRef("HV-B", "HV-B-IND")), insert.inputs());

        Executable update = assertInstanceOf(Executable.class, SqlBlockParser.parse(
                "EXEC SQL UPDATE T SET A = :HV-A INDICATOR :HV-IND WHERE K = :HV-K END-EXEC"));
        assertEquals(SqlOperation.UPDATE, update.operation());
        assertEquals("UPDATE T SET A = ? WHERE K = ?", update.sql());
        assertEquals(List.of(new HostRef("HV-A", "HV-IND"), host("HV-K")), update.inputs());

        Executable delete = assertInstanceOf(Executable.class, SqlBlockParser.parse(
                "EXEC SQL DELETE FROM ACCOUNT END-EXEC"));
        assertEquals("DELETE FROM ACCOUNT", delete.sql());
        assertTrue(delete.inputs().isEmpty());
    }

    @Test
    @DisplayName("DECLARE CURSOR と OPEN / FETCH / CLOSE、DECLARE TABLE、COMMIT WORK")
    void readsCursorsAndDeclarations() {
        CursorDeclaration cursor = assertInstanceOf(CursorDeclaration.class, SqlBlockParser.parse("""
                EXEC SQL DECLARE TRAN-COUNT-CURSOR CURSOR FOR
                   SELECT COUNT(*) FROM PROCTRAN
                   WHERE PROCTRAN_SORTCODE = :HV-QUERY-SORTCODE
                   FETCH FIRST 1 ROWS ONLY
                END-EXEC"""));
        assertEquals("TRAN-COUNT-CURSOR", cursor.cursor());
        assertEquals("SELECT COUNT(*) FROM PROCTRAN WHERE PROCTRAN_SORTCODE = ?"
                + " FETCH FIRST 1 ROWS ONLY", cursor.sql());
        assertEquals(List.of(host("HV-QUERY-SORTCODE")), cursor.inputs());

        Executable fetch = assertInstanceOf(Executable.class, SqlBlockParser.parse(
                "EXEC SQL FETCH FROM ACC-CURSOR INTO :HV-A, :HV-B END-EXEC"));
        assertEquals(SqlOperation.FETCH_CURSOR, fetch.operation());
        assertEquals("ACC-CURSOR", fetch.cursor());
        assertEquals(List.of(host("HV-A"), host("HV-B")), fetch.outputs());

        assertEquals(SqlOperation.OPEN_CURSOR, assertInstanceOf(Executable.class,
                SqlBlockParser.parse("EXEC SQL OPEN ACC-CURSOR END-EXEC")).operation());
        assertInstanceOf(SqlBlockParser.TableDeclaration.class, SqlBlockParser.parse(
                "EXEC SQL DECLARE ACCOUNT TABLE ( A CHAR(4), B DECIMAL(4, 2) ) END-EXEC"));
        assertEquals(new SqlBlockParser.TableDeclaration("STTESTER.CONTROL"), SqlBlockParser.parse(
                "EXEC SQL DECLARE STTESTER.CONTROL TABLE (CONTROL_NAME CHAR(32) NOT NULL) END-EXEC"));
        assertEquals(new SqlBlockParser.Transaction(true),
                SqlBlockParser.parse("EXEC SQL COMMIT WORK END-EXEC"));
        assertEquals(new SqlBlockParser.Transaction(false),
                SqlBlockParser.parse("EXEC SQL ROLLBACK END-EXEC"));
    }

    @Test
    @DisplayName("動的 SQL、WHENEVER、位置づけ更新、FOR UPDATE、修飾名は名前をつけて断る")
    void rejectsWhatItCannotTranslate() {
        for (String[] rejected : List.of(
                new String[] {"EXEC SQL PREPARE S1 FROM :TEXT END-EXEC", "PREPARE"},
                new String[] {"EXEC SQL WHENEVER SQLERROR GO TO X END-EXEC", "WHENEVER"},
                new String[] {"EXEC SQL UPDATE T SET A = 1 WHERE CURRENT OF C END-EXEC", "CURRENT OF"},
                new String[] {"EXEC SQL DECLARE C CURSOR FOR SELECT A FROM T FOR UPDATE END-EXEC",
                        "FOR UPDATE"},
                new String[] {"EXEC SQL SELECT A INTO :G.A FROM T END-EXEC", "qualified"},
                new String[] {"EXEC SQL SELECT A FROM T END-EXEC", "INTO ... FROM"})) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> SqlBlockParser.parse(rejected[0]), rejected[0]);
            assertTrue(failure.getMessage().contains(rejected[1]), failure.getMessage());
        }
    }
}
