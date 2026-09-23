package dev.cobolonjava.maven;

/**
 * 診断の出し先。Maven の {@code Log} に直に結ばず、試験から結果を読めるようにしておく。
 */
interface Report {

    void error(String message);

    void warn(String message);

    void info(String message);
}
