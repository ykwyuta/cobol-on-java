package dev.cobolonjava.ims.rdb;

import dev.cobolonjava.ims.store.DatabaseStoreException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * IMS のデータベースを置く表 (設計 78 §3.2、ADR-0013、暫定判断 P-160)。
 *
 * <p>ADR-0013 の主キー {@code (DBD_NAME, ROOT_KEY_RAW, HIERARCHY_PATH)} は、根のキーが重なる DBD
 * (順序フィールドが {@code M}、Bank-of-Z の CUSTACCS) とキーを持たない根を表せない。そこで同じキーの根の並びを
 * {@code ROOT_SEQ} として主キーに足す。{@code HIERARCHY_PATH} は PostgreSQL では {@code COLLATE "C"} を付ける。
 * 方言は H2・PostgreSQL・Db2 で、{@link Dialect} だけが差を知る。
 * ただし読み込むときは Java の側で並べ直すので、置き場の照合順序には頼らない。
 */
final class ImsSchema {

    private ImsSchema() {
    }

    /**
     * 置き場の方言。<b>製品ごとの差を知るのはここだけ</b>にする (設計 78 §2.2、暫定判断 P-160)。
     *
     * <p>Db2 の値は 2026-09-16 に Db2 12.1 へ直接訊いて決めた。{@code CREATE TABLE IF NOT EXISTS}、
     * {@code SELECT ... FOR UPDATE}、{@code DEFAULT CURRENT_TIMESTAMP} はそのまま通る。違うのは
     * {@code VARBINARY} の上限 (32672 byte) と、{@code SELECT} に {@code FROM} が要ることである。
     */
    enum Dialect {

        H2("VARBINARY(256)", "VARBINARY(32767)", "", "SELECT CURRENT_TIMESTAMP"),
        POSTGRESQL("BYTEA", "BYTEA", " COLLATE \"C\"", "SELECT CURRENT_TIMESTAMP"),
        DB2("VARBINARY(256)", "VARBINARY(32672)", "", "SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1");

        private final String key;
        private final String data;
        private final String ordered;
        private final String currentTimestamp;

        Dialect(String key, String data, String ordered, String currentTimestamp) {
            this.key = key;
            this.data = data;
            this.ordered = ordered;
            this.currentTimestamp = currentTimestamp;
        }

        /** 置き場の時計を読む文。Db2 は {@code FROM} の無い {@code SELECT} を受け付けない。 */
        String currentTimestampSql() {
            return currentTimestamp;
        }
    }

    /**
     * 製品名から方言を決める。
     *
     * <p>Db2 の製品名は機種を含む ({@code DB2/LINUXX8664}) ので、前方一致で見る。
     */
    private static Dialect dialectOf(String product) {
        if (product.equals("H2")) {
            return Dialect.H2;
        }
        if (product.equals("PostgreSQL")) {
            return Dialect.POSTGRESQL;
        }
        if (product.startsWith("DB2")) {
            return Dialect.DB2;
        }
        throw new DatabaseStoreException("the IMS tables are not defined for " + product
                + " yet; H2, PostgreSQL and Db2 are (provisional P-160)");
    }

    /**
     * 表が無ければ作る。
     *
     * @return この接続の方言。置き場が方言ごとの文を組むのに使う
     */
    static Dialect ensure(Connection connection) throws SQLException {
        Dialect dialect = dialectOf(connection.getMetaData().getDatabaseProductName());
        String key = dialect.key;
        String data = dialect.data;
        String ordered = dialect.ordered;
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS IMS_SEGMENT_STORE ("
                    + "DBD_NAME VARCHAR(8) NOT NULL, "
                    + "ROOT_KEY_RAW " + key + " NOT NULL, "
                    + "ROOT_SEQ INTEGER NOT NULL, "
                    + "HIERARCHY_PATH VARCHAR(256)" + ordered + " NOT NULL, "
                    + "SEG_NAME VARCHAR(8) NOT NULL, "
                    + "SEG_LEVEL SMALLINT NOT NULL, "
                    + "PARENT_PATH VARCHAR(256)" + ordered + " NOT NULL, "
                    + "SEQ_KEY_RAW " + key + ", "
                    + "SEG_LEN INTEGER NOT NULL, "
                    + "SEG_DATA " + data + " NOT NULL, "
                    + "CONSTRAINT PK_IMS_SEGMENT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW, ROOT_SEQ, HIERARCHY_PATH))");
            statement.execute("CREATE TABLE IF NOT EXISTS IMS_ROOT_INDEX ("
                    + "DBD_NAME VARCHAR(8) NOT NULL, "
                    + "ROOT_KEY_RAW " + key + " NOT NULL, "
                    + "ROOT_SEQ INTEGER NOT NULL, "
                    + "CONSTRAINT PK_IMS_ROOT PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW, ROOT_SEQ))");
            // ルートアンカーロックの行 (ADR-0015、P-161)。キーの根が重なっても 1 行で、確定のたびに版を上げる。
            // 根の行が消えても残すので、消したことも版で分かる
            // 処理済みの電文 (ADR-0014 の決定 2、P-163)。業務の更新と同じトランザクションで書く
            statement.execute("CREATE TABLE IF NOT EXISTS IMS_MESSAGE_INBOX ("
                    + "MESSAGE_ID VARCHAR(255) NOT NULL, "
                    + "RECORDED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                    + "CONSTRAINT PK_IMS_MESSAGE_INBOX PRIMARY KEY (MESSAGE_ID))");
            // 記号 CHKP が退避した域 (P-164)。業務の更新と同じ確定で書くので、再始動した域と
            // データベースの状態が揃う。域は最大 7 つで、AREA_SEQ は CHKP に書いた順である
            statement.execute("CREATE TABLE IF NOT EXISTS IMS_CHECKPOINT ("
                    + "PSB_NAME VARCHAR(8) NOT NULL, "
                    + "CHKP_ID VARCHAR(8) NOT NULL, "
                    + "AREA_SEQ SMALLINT NOT NULL, "
                    + "AREA_LEN INTEGER NOT NULL, "
                    + "AREA_DATA " + data + " NOT NULL, "
                    + "RECORDED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                    + "CONSTRAINT PK_IMS_CHECKPOINT PRIMARY KEY (PSB_NAME, CHKP_ID, AREA_SEQ))");
            // 取引コードのキューを読む領域を 1 つに限る借用 (P-167)。心拍が古くなれば引き継げる
            statement.execute("CREATE TABLE IF NOT EXISTS IMS_QUEUE_LEASE ("
                    + "TRANSACTION_CODE VARCHAR(64) NOT NULL, "
                    + "LEASE_OWNER VARCHAR(128) NOT NULL, "
                    + "HEARTBEAT_AT TIMESTAMP NOT NULL, "
                    + "CONSTRAINT PK_IMS_QUEUE_LEASE PRIMARY KEY (TRANSACTION_CODE))");
            statement.execute("CREATE TABLE IF NOT EXISTS IMS_ROOT_LOCK ("
                    + "DBD_NAME VARCHAR(8) NOT NULL, "
                    + "ROOT_KEY_RAW " + key + " NOT NULL, "
                    + "VERSION BIGINT NOT NULL, "
                    + "CONSTRAINT PK_IMS_ROOT_LOCK PRIMARY KEY (DBD_NAME, ROOT_KEY_RAW))");
        }
        return dialect;
    }
}
