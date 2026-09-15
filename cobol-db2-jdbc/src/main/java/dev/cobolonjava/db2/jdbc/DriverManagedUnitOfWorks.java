package dev.cobolonjava.db2.jdbc;

import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.UnitOfWork;
import java.sql.Connection;
import java.util.Objects;

/**
 * driver-managed の UOW が専有する native lease の connection を、同じ UOW に入れたい部品へ渡す。
 *
 * <p>STRICT の会話ストアは、業務の SQL と同じ connection・同じ UOW で会話の表を更新する必要がある
 * (設計 77 §4.6、暫定判断 P-143)。connection を渡すのは UOW が ACTIVE の間だけであり、受け取った側が
 * commit / rollback / close / autoCommit の変更をしてはならない。UOW の境界は port だけが持つ。
 */
public final class DriverManagedUnitOfWorks {

    private DriverManagedUnitOfWorks() {
    }

    /**
     * @throws Db2ProfileMismatchException driver-managed の UOW でないとき
     * @throws dev.cobolonjava.db2.UnitOfWorkStateException UOW が ACTIVE でないとき、または別の thread から呼んだとき
     */
    public static Connection connection(UnitOfWork unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (!(unitOfWork instanceof DriverManagedJdbcUnitOfWork nativeUnit)) {
            throw new Db2ProfileMismatchException(
                    "a native lease connection requires a driver-managed JDBC UOW");
        }
        return nativeUnit.connection();
    }
}
