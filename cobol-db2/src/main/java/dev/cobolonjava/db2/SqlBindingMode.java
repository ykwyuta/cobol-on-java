package dev.cobolonjava.db2;

/** SQL host variableの入力・出力方向。 */
public enum SqlBindingMode {
    INPUT(true, false),
    OUTPUT(false, true),
    INOUT(true, true);

    private final boolean input;
    private final boolean output;

    SqlBindingMode(boolean input, boolean output) {
        this.input = input;
        this.output = output;
    }

    public boolean isInput() {
        return input;
    }

    public boolean isOutput() {
        return output;
    }
}
