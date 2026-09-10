package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.TruncMode;
import java.util.Objects;

/** JDBC型を漏らさずCOBOL host variableの物理表現を記述する。 */
public sealed interface SqlValueDescriptor {

    /** 固定長文字。バイト長はhost variableのDataViewから得る。 */
    record FixedCharacter(CodePage codePage) implements SqlValueDescriptor {
        public FixedCharacter {
            Objects.requireNonNull(codePage, "codePage");
        }
    }

    /** COMP-3。 */
    record PackedDecimal(int digits, int scale, boolean signed)
            implements SqlValueDescriptor {
        public PackedDecimal {
            requireDecimalShape(digits, scale);
        }
    }

    /** 数字DISPLAY。 */
    record ZonedDecimal(
            int digits, int scale, SignPosition signPosition, CodePage codePage)
            implements SqlValueDescriptor {
        public ZonedDecimal {
            requireDecimalShape(digits, scale);
            Objects.requireNonNull(signPosition, "signPosition");
            Objects.requireNonNull(codePage, "codePage");
        }
    }

    /** COMP / BINARY。 */
    record BinaryInteger(int digits, int scale, TruncMode truncMode)
            implements SqlValueDescriptor {
        public BinaryInteger {
            requireDecimalShape(digits, scale);
            Objects.requireNonNull(truncMode, "truncMode");
            if (truncMode == TruncMode.OPT) {
                throw new IllegalArgumentException(
                        "SQL binary descriptor requires resolved TRUNC mode");
            }
            BinaryDecimal.byteLength(digits);
        }
    }

    private static void requireDecimalShape(int digits, int scale) {
        if (digits < 1 || scale < 0 || scale > digits) {
            throw new IllegalArgumentException(
                    "SQL decimal descriptor requires digits >= scale >= 0: "
                            + digits + ", " + scale);
        }
    }
}
