package dev.cobolonjava.db2;

/** adapterが公開する一つのUOWの観測可能な状態。 */
public enum UnitOfWorkState {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK,
    FAILED
}
