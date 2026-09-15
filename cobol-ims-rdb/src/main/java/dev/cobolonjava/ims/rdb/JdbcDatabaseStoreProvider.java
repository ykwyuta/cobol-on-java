package dev.cobolonjava.ims.rdb;

import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.ims.store.DatabaseStoreException;
import dev.cobolonjava.ims.store.DatabaseStoreProvider;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * システムプロパティ {@code cobol.ims.jdbc.url} を指定したときだけ、RDB の置き場を差し込む (P-160)。
 *
 * <p>利用者とパスワードは {@code cobol.ims.jdbc.user} / {@code cobol.ims.jdbc.password}。接続は領域ごとに開いて閉じる。
 * Spring の DataSource へのつなぎ込みはまだ無い。
 */
public final class JdbcDatabaseStoreProvider implements DatabaseStoreProvider {

    public static final String URL = "cobol.ims.jdbc.url";
    public static final String USER = "cobol.ims.jdbc.user";
    public static final String PASSWORD = "cobol.ims.jdbc.password";

    @Override
    public DatabaseStore open(ProgramContext context) {
        String url = System.getProperty(URL);
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return new JdbcDatabaseStore(DriverManager.getConnection(url, System.getProperty(USER, ""),
                    System.getProperty(PASSWORD, "")));
        } catch (SQLException e) {
            throw new DatabaseStoreException("cannot connect to the IMS database store " + url, e);
        }
    }
}
