package dev.cobolonjava.runtime.program;

import java.util.Objects;

/**
 * CALLを巻き戻し、所有programの段落state machineへ制御を戻すsubsystem共通signal。
 *
 * <p>業務障害ではなくCOBOLの正常な非局所制御であり、生成programのCALL境界だけが受け止める。
 */
public final class ProgramTargetTransfer extends ProgramControlTransfer {

    private final Object owner;
    private final int target;

    public ProgramTargetTransfer(Object owner, int target, String reason) {
        super(Objects.requireNonNull(reason, "reason") + " to paragraph " + target);
        this.owner = Objects.requireNonNull(owner, "owner");
        if (target < 0) {
            throw new IllegalArgumentException("program target must be non-negative");
        }
        this.target = target;
    }

    /** 現在のprogram入口がこのtransferの受取先か。 */
    public boolean ownedBy(Object invocation) {
        return owner == Objects.requireNonNull(invocation, "invocation");
    }

    public int target() {
        return target;
    }
}
