package dev.cobolonjava.db2.jdbc;

/** task専用connectionをproviderへ返すときの再利用可否。 */
public enum LeaseReleaseDisposition {
    REUSABLE,
    DISCARD
}
