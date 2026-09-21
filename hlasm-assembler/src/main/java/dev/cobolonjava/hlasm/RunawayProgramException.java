package dev.cobolonjava.hlasm;

/**
 * 命令数の上限まで戻ってこなかったこと。
 *
 * <p>プログラム割込み ({@link MachineException}) とは<b>別の例外にしてある</b>。
 * 割込みは資産の側の出来事であり、観測できる結果 ({@code S0C7} など) として数えてよい。
 * 戻ってこないのは違う。測定の側から見れば「この 1 本は結果が取れなかった」であって、
 * 「結果が期待と違った」ではない。同じ例外にすると、暴走した 1 本が
 * 「異常終了した資産」として結果の不一致に数えられ、数が嘘をつく。
 *
 * <p>返ってこない翻訳系は、断る翻訳系より悪い (CLAUDE.md §6)。実行についても同じである。
 */
public final class RunawayProgramException extends RuntimeException {

    private final long steps;

    public RunawayProgramException(long steps) {
        super("the program did not return within " + steps + " instructions");
        this.steps = steps;
    }

    public long steps() {
        return steps;
    }
}
