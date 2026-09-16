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
 * ただし読み込むときは Java の側で並べ直すので、置き場の照合順序には頼らない。
 */
final class ImsSchema {

    private ImsSchema() {
    }

    /** 表が無ければ作る。 */
    static void ensure(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        String key;
        String data;
        String ordered;
        switch (product) {
            case "H2" -> {
                key = "VARBINARY(256)";
                data = "VARBINARY(32767)";
                ordered = "";
            }
            case "PostgreSQL" -> {
                key = "BYTEA";
                data = "BYTEA";
                ordered = " COLLATE \"C\"";
            }
            default -> throw new DatabaseStoreException("the IMS tables are not defined for " + product
                    + " yet; H2 and PostgreSQL are (provisional P-160)");
        }
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
    }
}
