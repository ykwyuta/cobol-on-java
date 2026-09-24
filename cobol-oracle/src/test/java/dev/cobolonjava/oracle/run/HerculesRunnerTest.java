package dev.cobolonjava.oracle.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class HerculesRunnerTest {

    @Test
    void ignoresOnlyTheOptionalRexxStartupError() {
        String log = String.join("\n",
                "HHC17511E REXX() Could not enable default Rexx package",
                "HHC02205E Invalid storage alteration command");
        assertEquals(List.of("HHC02205E Invalid storage alteration command"),
                HerculesRunner.consoleErrors(log));
    }
}
