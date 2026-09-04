package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 日付と時刻の特殊レジスタ (要件 FR-060、テスト時の固定は FR-204)。
 *
 * <p>{@code ACCEPT} が受け取る値である。<b>どれも符号なし整数の表示形式</b>であり、
 * 受け取る項目が英数字なら数字がそのまま並び、数値なら整数として読まれる。
 * 桁数は形式ごとに決まっている。
 *
 * <table>
 *   <caption>形式と桁数</caption>
 *   <tr><th>指定</th><th>形</th><th>桁</th></tr>
 *   <tr><td>{@code DATE}</td><td>YYMMDD</td><td>6</td></tr>
 *   <tr><td>{@code DATE YYYYMMDD}</td><td>YYYYMMDD</td><td>8</td></tr>
 *   <tr><td>{@code DAY}</td><td>YYDDD</td><td>5</td></tr>
 *   <tr><td>{@code DAY YYYYDDD}</td><td>YYYYDDD</td><td>7</td></tr>
 *   <tr><td>{@code DAY-OF-WEEK}</td><td>D</td><td>1</td></tr>
 *   <tr><td>{@code TIME}</td><td>HHMMSSss</td><td>8</td></tr>
 * </table>
 *
 * <p>{@code DAY-OF-WEEK} は<b>月曜が 1、日曜が 7</b> である。
 *
 * <p>時計を外から渡せるようにしてあるのは、試験で値を固定するためである。
 * 実行のたびに変わる値は、そのままでは試験に書けない。
 */
public final class SpecialRegisters {

    private SpecialRegisters() {
    }

    /** 形式。 */
    public enum Form {
        DATE, DATE_YYYYMMDD, DAY, DAY_YYYYDDD, DAY_OF_WEEK, TIME;

        /** この形式が持つ桁数。 */
        public int digits() {
            return switch (this) {
                case DATE -> 6;
                case DATE_YYYYMMDD, TIME -> 8;
                case DAY -> 5;
                case DAY_YYYYDDD -> 7;
                case DAY_OF_WEEK -> 1;
            };
        }
    }

    /** 指定した形式の値を、実行時のコードページの数字として返す。 */
    public static byte[] valueOf(Form form, Clock clock, CodePage codePage) {
        LocalDateTime now = LocalDateTime.now(clock);
        long value = switch (form) {
            case DATE -> (now.getYear() % 100) * 10000L
                    + now.getMonthValue() * 100L + now.getDayOfMonth();
            case DATE_YYYYMMDD -> now.getYear() * 10000L
                    + now.getMonthValue() * 100L + now.getDayOfMonth();
            case DAY -> (now.getYear() % 100) * 1000L + now.getDayOfYear();
            case DAY_YYYYDDD -> now.getYear() * 1000L + now.getDayOfYear();
            // java.time は月曜が 1 である。COBOL と同じ
            case DAY_OF_WEEK -> now.getDayOfWeek().getValue();
            case TIME -> now.getHour() * 1000000L + now.getMinute() * 10000L
                    + now.getSecond() * 100L + now.getNano() / 10_000_000L;
        };
        return digits(value, form.digits(), codePage);
    }

    /** 値を桁数ぶんの数字にする。上位は 0 で埋める。 */
    private static byte[] digits(long value, int width, CodePage codePage) {
        byte[] out = new byte[width];
        long rest = value;
        for (int i = width - 1; i >= 0; i--) {
            out[i] = codePage.digit((int) (rest % 10));
            rest /= 10;
        }
        return out;
    }
}
