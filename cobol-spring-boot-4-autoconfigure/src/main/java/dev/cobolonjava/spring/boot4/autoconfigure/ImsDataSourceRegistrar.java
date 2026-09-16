package dev.cobolonjava.spring.boot4.autoconfigure;

import dev.cobolonjava.ims.rdb.JdbcDatabaseStoreProvider;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;

/**
 * 容器の {@link DataSource} を IMS の置き場へ預ける (暫定判断 P-169)。
 *
 * <p>IMS の置き場は {@code ServiceLoader} で差し込まれるので、容器の bean を直には見つけられない。
 * ここが起きたときに預け、畳むときに戻す。
 *
 * <p>預けた接続元から<b>置き場が自前の接続を取り、同期点で自分で確定する</b>。Spring が管理する
 * トランザクションには相乗りしない (P-169)。
 */
public final class ImsDataSourceRegistrar implements DisposableBean {

    public ImsDataSourceRegistrar(DataSource dataSource) {
        JdbcDatabaseStoreProvider.useDataSource(dataSource);
    }

    @Override
    public void destroy() {
        JdbcDatabaseStoreProvider.useDataSource(null);
    }
}
