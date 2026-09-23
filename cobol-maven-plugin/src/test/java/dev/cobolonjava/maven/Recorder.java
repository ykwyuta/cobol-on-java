package dev.cobolonjava.maven;

import java.util.ArrayList;
import java.util.List;

/** 出された診断を重大度付きで溜める。 */
final class Recorder implements Report {

    final List<String> lines = new ArrayList<>();

    @Override
    public void error(String message) {
        lines.add("ERROR " + message);
    }

    @Override
    public void warn(String message) {
        lines.add("WARN " + message);
    }

    @Override
    public void info(String message) {
        lines.add("INFO " + message);
    }
}
