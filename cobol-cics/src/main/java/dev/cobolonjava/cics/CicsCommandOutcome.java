package dev.cobolonjava.cics;

import java.util.Objects;

/** EIBRESP/EIBRESP2と制御結果をtransport状態から分離したcommand結果。 */
public record CicsCommandOutcome(int responseCode, int responseCode2, CicsControl control) {

    public CicsCommandOutcome {
        Objects.requireNonNull(control, "control");
    }
}
