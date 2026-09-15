package dev.cobolonjava.db2.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.db2.UnitOfWorkStateException;
import java.sql.Connection;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 会話ストアが native lease の connection を受け取る口 (暫定判断 P-143)。 */
@Tag("V1")
class DriverManagedUnitOfWorksTest {

    private static final UnitOfWorkOptions OPTIONS = new UnitOfWorkOptions(
            Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, Duration.ofSeconds(30), false, true);

    @Test
    @DisplayName("ACTIVEな間だけleaseのconnectionを渡し、commit後の古いUOWとdriver-managedでないUOWは断る")
    void exposesLeaseConnectionOnlyWhileActive() {
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(new DriverManagerDb2NativeConnectionProvider(
                "jdbc:h2:mem:uows-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", new Properties()));
        try {
            UnitOfWork first = port.begin(OPTIONS);
            Connection lease = DriverManagedUnitOfWorks.connection(first);
            first.commit();
            assertThrows(UnitOfWorkStateException.class, () -> DriverManagedUnitOfWorks.connection(first));

            UnitOfWork second = port.begin(OPTIONS);
            assertSame(lease, DriverManagedUnitOfWorks.connection(second));
            second.rollback(RollbackReason.cleanup());
        } finally {
            port.close();
        }
        assertThrows(Db2ProfileMismatchException.class, () -> DriverManagedUnitOfWorks.connection(new UnitOfWork() {
            @Override
            public Db2ExecutionProfile profile() {
                return Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD;
            }

            @Override
            public ResourceLeaseId resourceLeaseId() {
                return new ResourceLeaseId("other");
            }

            @Override
            public UnitOfWorkState state() {
                return UnitOfWorkState.ACTIVE;
            }

            @Override
            public void commit() {
            }

            @Override
            public void rollback(RollbackReason reason) {
            }
        }));
    }
}
