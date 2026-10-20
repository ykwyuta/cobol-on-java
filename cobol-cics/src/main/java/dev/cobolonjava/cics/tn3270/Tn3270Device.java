package dev.cobolonjava.cics.tn3270;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 端末が名乗った装置の型 (RFC 1576 の terminal type、RFC 2355 の DEVICE-TYPE)。
 *
 * <p>画面の大きさは型番から決める。既定の大きさは どの model でも 24 x 80 であり、代替の大きさは
 * model 2 が 24 x 80、3 が 32 x 80、4 が 43 x 80、5 が 27 x 132 である (3270 の公開仕様)。
 * 末尾の {@code -E} は拡張属性 (色、強調) を受けることを表す。
 *
 * <p><b>実機と突き合わせていない</b>: 拡張属性の有無を {@code -E} だけで決め、Read Partition Query で
 * 端末に聞いていない (暫定判断 P-191)。{@code IBM-DYNAMIC} (大きさを Query で決める型) は受けない。
 *
 * @param name 端末が名乗った型の名前 (例 {@code IBM-3278-2-E})
 */
public record Tn3270Device(String name, int model, boolean extended) {

    private static final Pattern TYPE = Pattern.compile("IBM-327[89]-([2-5])(-E)?");

    public Tn3270Device {
        Objects.requireNonNull(name, "name");
        if (model < 2 || model > 5) {
            throw new IllegalArgumentException("3270 model must be 2 to 5: " + model);
        }
    }

    /** 型の名前を読む。表示端末 (3278 / 3279) の model 2〜5 でなければ空。 */
    public static Optional<Tn3270Device> of(String name) {
        String upper = Objects.requireNonNull(name, "name").toUpperCase(Locale.ROOT);
        Matcher matcher = TYPE.matcher(upper);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Tn3270Device(upper, Integer.parseInt(matcher.group(1)), matcher.group(2) != null));
    }

    public int defaultRows() {
        return 24;
    }

    public int defaultColumns() {
        return 80;
    }

    public int alternateRows() {
        return switch (model) {
            case 3 -> 32;
            case 4 -> 43;
            case 5 -> 27;
            default -> 24;
        };
    }

    public int alternateColumns() {
        return model == 5 ? 132 : 80;
    }
}
