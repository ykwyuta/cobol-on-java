package dev.cobolonjava.maven;

import org.apache.maven.plugin.logging.Log;

/** Maven のログへ診断を出す。 */
final class MavenReport {

    private MavenReport() {
    }

    static Report of(Log log) {
        return new Report() {
            @Override
            public void error(String message) {
                log.error(message);
            }

            @Override
            public void warn(String message) {
                log.warn(message);
            }

            @Override
            public void info(String message) {
                log.info(message);
            }
        };
    }
}
