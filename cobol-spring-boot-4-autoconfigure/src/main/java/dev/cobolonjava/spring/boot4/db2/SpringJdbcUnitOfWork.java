package dev.cobolonjava.spring.boot4.db2;

import dev.cobolonjava.db2.UnitOfWork;
import javax.sql.DataSource;

/** SQL executorがtransaction-bound resource identityとthread所有を検査する内部契約。 */
interface SpringJdbcUnitOfWork extends UnitOfWork {

    void verifyUsableBy(DataSource dataSource);

    void registerResource(AutoCloseable resource);

    void unregisterResource(AutoCloseable resource);
}
