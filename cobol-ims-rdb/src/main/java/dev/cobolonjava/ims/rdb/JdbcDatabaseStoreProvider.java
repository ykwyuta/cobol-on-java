package dev.cobolonjava.ims.rdb;

import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.ims.store.DatabaseStoreException;
import dev.cobolonjava.ims.store.DatabaseStoreProvider;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;

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

    /**
     * Spring などの容器が渡した接続元 (P-169)。
     *
     * <p>{@code DatabaseStores} は {@link java.util.ServiceLoader} で差し込みを探すので、容器の bean を
     * そのままでは見つけられない。容器の側から<b>ここへ預ける</b>ことで橋渡しする。
     */
    private static volatile DataSource dataSource;

    /**
     * 接続元を預ける。容器が起こすときに呼び、畳むときに {@code null} で戻す (P-169)。
     *
     * <p>置き場は<b>ここから自前の接続を取り、同期点で自分で確定する</b>。容器が管理するトランザクションには
     * 相乗りしない。IMS の同期点が確定の時機を決めるからである。
     */
    public static void useDataSource(DataSource value) {
        dataSource = value;
    }

    @Override
    public DatabaseStore open(ProgramContext context) {
        String url = System.getProperty(URL);
        // 明示の URL は、預かった接続元より優先する
        if (url != null && !url.isBlank()) {
            try {
                return new JdbcDatabaseStore(DriverManager.getConnection(url, System.getProperty(USER, ""),
                        System.getProperty(PASSWORD, "")));
            } catch (SQLException e) {
                throw new DatabaseStoreException("cannot connect to the IMS database store " + url, e);
            }
        }
        DataSource source = dataSource;
        if (source == null) {
            return null;
        }
        try {
            return new JdbcDatabaseStore(source.getConnection());
        } catch (SQLException e) {
            throw new DatabaseStoreException("cannot take a connection for the IMS database store"
                    + " from the data source given by the container", e);
        }
    }
}
