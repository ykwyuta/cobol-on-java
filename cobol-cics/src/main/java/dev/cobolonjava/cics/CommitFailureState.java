package dev.cobolonjava.cics;

/** commit例外時に安全な後処理を選ぶための既知状態。 */
public enum CommitFailureState {
    NOT_COMMITTED,
    UNKNOWN
}
