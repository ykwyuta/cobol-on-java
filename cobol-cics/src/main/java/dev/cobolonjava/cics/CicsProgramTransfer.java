package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.program.ProgramControlTransfer;
import java.util.Objects;

/** RETURNまたはXCTLをprogram stackからtask executorへ戻す内部正常制御。 */
public final class CicsProgramTransfer extends ProgramControlTransfer {

    private final CicsControl control;

    public CicsProgramTransfer(CicsControl control) {
        super("CICS program control transfer");
        this.control = Objects.requireNonNull(control, "control");
        if (!(control instanceof TransferControl) && !(control instanceof TaskCompletion)) {
            throw new IllegalArgumentException("only XCTL or RETURN may transfer program control");
        }
    }

    public CicsControl control() {
        return control;
    }
}
