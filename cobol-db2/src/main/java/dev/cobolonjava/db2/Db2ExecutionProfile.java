package dev.cobolonjava.db2;

/** 一つのtask内で固定するDb2 transaction／connection所有方式。 */
public enum Db2ExecutionProfile {
    /** Spring JDBC transaction managerがUOWとconnectionを所有する通常経路。 */
    SPRING_MANAGED,
    /** Db2 adapterがtask専用leaseを複数commit間で所有するWITH HOLD経路。 */
    DB2_DRIVER_MANAGED_HOLD
}
