package dev.cobolonjava.runtime.le;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.storage.DataView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Language Environment の日付の callable service (暫定判断 P-139)。
 *
 * <p>形と feedback code は z/OS Language Environment Programming Reference の CEEDAYS / CEELOCT の頁による。
 * {@link #register} で program catalog に登録すると、COBOL の {@code CALL "CEEDAYS"} から呼べる。
 *
 * <h2>feedback code</h2>
 * <p>12 byte の condition token。成功は binary zero。失敗は Severity (半語)、Msg-No (半語)、Case-Sev-Ctl (1 byte)、
 * Facility-ID ({@code CEE}、3 文字)、I-S-Info (全語、0) を置く。Case-Sev-Ctl を 1 とするのは IBM COBOL for Linux の
 * 文書「常に 1」による。z/OS の値とは突き合わせていない。
 */
public final class LanguageEnvironmentServices {

    /** Lilian day 0 にあたる日 (1582 年 10 月 14 日)。1582 年 10 月 15 日が 1 である。 */
    private static final long LILIAN_ORIGIN = LocalDate.of(1582, 10, 14).toEpochDay();
    private static final LocalDate FIRST_DATE = LocalDate.of(1582, 10, 15);
    private static final int TOKEN_LENGTH = 12;

    /** feedback code の記号名、severity、message 番号。 */
    record Feedback(String symbol, int severity, int messageNumber) {
    }

    static final Feedback CEE000 = new Feedback("CEE000", 0, 0);
    /** 入力のデータが足りない。 */
    static final Feedback CEE2EB = new Feedback("CEE2EB", 3, 2507);
    /** 日の値が正しくない。 */
    static final Feedback CEE2EC = new Feedback("CEE2EC", 3, 2508);
    /** 扱える範囲 (1582-10-15〜9999-12-31) の外。 */
    static final Feedback CEE2EH = new Feedback("CEE2EH", 3, 2513);
    /** 月の値が正しくない。 */
    static final Feedback CEE2EL = new Feedback("CEE2EL", 3, 2517);
    /** 数字であるべき欄に数字でない文字がある。 */
    static final Feedback CEE2EO = new Feedback("CEE2EO", 3, 2520);

    private LanguageEnvironmentServices() {
    }

    /** CEEDAYS と CEELOCT を登録する。 */
    public static ProgramCatalog.Builder register(ProgramCatalog.Builder builder) {
        return builder
                .javaProgram("CEEDAYS", () -> (context, arguments) -> ceedays(context.codePage(), arguments))
                .javaProgram("CEELOCT", () -> (context, arguments) ->
                        ceeloct(context.codePage(), context.clock(), arguments))
                .javaProgram("CEEDATE", () -> (context, arguments) ->
                        format(context.codePage(), arguments, false))
                .javaProgram("CEEDATM", () -> (context, arguments) ->
                        format(context.codePage(), arguments, true))
                .javaProgram("CEESECS", () -> (context, arguments) ->
                        ceesecs(context.codePage(), arguments))
                .javaProgram("CEENCOD", () -> (context, arguments) -> ceencod(arguments))
                .javaProgram("CEEDCOD", () -> (context, arguments) -> ceedcod(arguments));
    }

    /**
     * 実行単位がいつも持っているサービスの表。z/OS では LE がいつも在るので、
     * 利用者のカタログに無い {@code CEE...} の名前はここから引く。
     */
    public static final ProgramCatalog BUILT_IN = register(ProgramCatalog.builder()).build();

    private static final String[] MONTHS = {"JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
        "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"};
    private static final String[] DAYS = {"SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
        "FRIDAY", "SATURDAY"};

    /**
     * Lilian の秒を絵文字列で字にする (CEEDATE と CEEDATM の共通の部分)。
     *
     * <p>持つ絵は YYYY・YY・MM・MMM/Mmm・DD・DDD・HH・MI・SS・WWW/Www と、ほかの字 (そのまま写す) で
     * ある。並びは Zeus (同じ権利者の処理系) の {@code vm/le.rs} と同じにした。ほかの絵
     * (時代、午前午後、小数の秒など) は断る。実機とは突き合わせていない。
     */
    static String formatLilian(long day, int secondOfDay, String picture) {
        LocalDate date = LocalDate.ofEpochDay(day + LILIAN_ORIGIN);
        // 1582 年 10 月 15 日 (Lilian の 1 日目) は金曜である
        int weekday = (int) Math.floorMod(day + 4, 7L);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < picture.length(); ) {
            String rest = picture.substring(i);
            if (rest.startsWith("YYYY")) {
                out.append(String.format("%04d", date.getYear()));
                i += 4;
            } else if (rest.startsWith("YY")) {
                out.append(String.format("%02d", date.getYear() % 100));
                i += 2;
            } else if (rest.startsWith("MMM") || rest.startsWith("Mmm")) {
                out.append(named(MONTHS[date.getMonthValue() - 1], rest));
                i += 3;
            } else if (rest.startsWith("MM")) {
                out.append(String.format("%02d", date.getMonthValue()));
                i += 2;
            } else if (rest.startsWith("MI")) {
                out.append(String.format("%02d", secondOfDay % 3600 / 60));
                i += 2;
            } else if (rest.startsWith("DDD")) {
                out.append(String.format("%03d", date.getDayOfYear()));
                i += 3;
            } else if (rest.startsWith("DD")) {
                out.append(String.format("%02d", date.getDayOfMonth()));
                i += 2;
            } else if (rest.startsWith("HH")) {
                out.append(String.format("%02d", secondOfDay / 3600));
                i += 2;
            } else if (rest.startsWith("SS")) {
                out.append(String.format("%02d", secondOfDay % 60));
                i += 2;
            } else if (rest.startsWith("WWW") || rest.startsWith("Www")) {
                out.append(named(DAYS[weekday], rest));
                i += 3;
            } else if (Character.isLetterOrDigit(picture.charAt(i))) {
                throw new IllegalArgumentException(
                        "this date/time picture string is not supported: " + picture);
            } else {
                out.append(picture.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** 名前の頭 3 字。絵の 2 字目が小文字なら頭だけ大文字にする。 */
    private static String named(String name, String picture) {
        String three = name.substring(0, 3);
        return Character.isLowerCase(picture.charAt(1))
                ? three.charAt(0) + three.substring(1).toLowerCase(java.util.Locale.ROOT) : three;
    }

    /**
     * CEEDATE (input_Lilian_date, picture_string, output_char_date, fc) と
     * CEEDATM (input_seconds, picture_string, output_timestamp, fc)。
     * 出力は 80 字で、右は空白で埋める。
     */
    static void format(CodePage codePage, List<DataView> arguments, boolean seconds) {
        String service = seconds ? "CEEDATM" : "CEEDATE";
        requireArguments(arguments, 4, service);
        String picture = vstring(codePage, arguments.get(1), service + " picture_string")
                .stripTrailing();
        DataView output = arguments.get(2);
        DataView fc = requireLength(arguments.get(3), TOKEN_LENGTH, service + " fc");
        long total;
        if (seconds) {
            DataView input = requireLength(arguments.get(0), Long.BYTES, "CEEDATM input_seconds");
            total = Ops.readFloat(input.storage(), input.offset(), Long.BYTES).toBigDecimal()
                    .setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
        } else {
            DataView input = requireLength(arguments.get(0), Integer.BYTES,
                    "CEEDATE input_Lilian_date");
            total = java.nio.ByteBuffer.wrap(input.toByteArray()).getInt() * 86_400L;
        }
        byte[] text = new byte[output.length()];
        java.util.Arrays.fill(text, codePage.space());
        if (total < 86_400L) {
            // 範囲の外。CEEDATM は CEE2EL、CEEDATE は CEE2EG を返し、出力は空白にする
            output.setBytes(text);
            token(codePage, fc, seconds ? CEE2EL : new Feedback("CEE2EG", 3, 2512));
            return;
        }
        byte[] encoded = codePage.encode(formatLilian(total / 86_400L, (int) (total % 86_400L),
                picture));
        System.arraycopy(encoded, 0, text, 0, Math.min(encoded.length, text.length));
        output.setBytes(text);
        token(codePage, fc, CEE000);
    }

    /**
     * CEESECS (input_timestamp, picture_string, output_seconds, fc)。日付の部分は CEEDAYS と
     * 同じに検査し、HH・MI・SS を足す。
     */
    static void ceesecs(CodePage codePage, List<DataView> arguments) {
        requireArguments(arguments, 4, "CEESECS");
        String stamp = vstring(codePage, arguments.get(0), "CEESECS input_timestamp");
        String picture = vstring(codePage, arguments.get(1), "CEESECS picture_string");
        DataView output = requireLength(arguments.get(2), Long.BYTES, "CEESECS output_seconds");
        DataView fc = requireLength(arguments.get(3), TOKEN_LENGTH, "CEESECS fc");
        StringBuilder date = new StringBuilder();
        StringBuilder datePicture = new StringBuilder();
        int time = 0;
        for (int i = 0; i < picture.length() && i < stamp.length(); i++) {
            char c = picture.charAt(i);
            if (c == 'Y' || c == 'D' || (c == 'M' && !picture.startsWith("MI", i))) {
                date.append(stamp.charAt(i));
                datePicture.append(c);
            } else if (picture.startsWith("HH", i) || picture.startsWith("MI", i)
                    || picture.startsWith("SS", i)) {
                String field = stamp.substring(i, Math.min(stamp.length(), i + 2));
                if (!field.chars().allMatch(Character::isDigit) || field.length() != 2) {
                    output.setBytes(new byte[Long.BYTES]);
                    token(codePage, fc, CEE2EO);
                    return;
                }
                time += Integer.parseInt(field) * (c == 'H' ? 3600 : c == 'M' ? 60 : 1);
                i++;
            }
        }
        Feedback feedback = parse(date.toString(), datePicture.toString());
        if (feedback != null) {
            output.setBytes(new byte[Long.BYTES]);
            token(codePage, fc, feedback);
            return;
        }
        long lilian = lilianDate(date.toString(), datePicture.toString()) - LILIAN_ORIGIN;
        Ops.storeFloat(Decimal.parse(Long.toString(lilian * 86_400L + time)), output.storage(),
                output.offset(), Long.BYTES);
        token(codePage, fc, CEE000);
    }

    /**
     * CEENCOD (c_1, c_2, case, severity, control, facility_ID, i_s_info, cond_token, fc)。
     * 条件トークンは 12 byte で、c_1 と c_2 の半語、case (2 ビット)・severity (3 ビット)・
     * control (3 ビット) を詰めた 1 byte、facility の 3 字、i_s_info の全語である。
     * 値の範囲の検査は持たない (範囲の外の値は断る)。
     */
    static void ceencod(List<DataView> arguments) {
        requireArguments(arguments, 9, "CEENCOD");
        int kind = half(arguments.get(2));
        int severity = half(arguments.get(3));
        int control = half(arguments.get(4));
        if (kind < 1 || kind > 2 || severity < 0 || severity > 4 || control < 0 || control > 1) {
            throw new IllegalArgumentException("CEENCOD with case " + kind + ", severity "
                    + severity + ", control " + control + " is not supported yet");
        }
        byte[] token = new byte[TOKEN_LENGTH];
        System.arraycopy(arguments.get(0).toByteArray(), 0, token, 0, 2);
        System.arraycopy(arguments.get(1).toByteArray(), 0, token, 2, 2);
        token[4] = (byte) (kind << 6 | severity << 3 | control);
        System.arraycopy(arguments.get(5).toByteArray(), 0, token, 5, 3);
        System.arraycopy(arguments.get(6).toByteArray(), 0, token, 8, 4);
        requireLength(arguments.get(7), TOKEN_LENGTH, "CEENCOD cond_token").setBytes(token);
        requireLength(arguments.get(8), TOKEN_LENGTH, "CEENCOD fc")
                .setBytes(new byte[TOKEN_LENGTH]);
    }

    /** CEEDCOD (cond_token, c_1, c_2, case, severity, control, facility_ID, i_s_info, fc)。 */
    static void ceedcod(List<DataView> arguments) {
        requireArguments(arguments, 9, "CEEDCOD");
        byte[] token = requireLength(arguments.get(0), TOKEN_LENGTH, "CEEDCOD cond_token")
                .toByteArray();
        arguments.get(1).setBytes(java.util.Arrays.copyOfRange(token, 0, 2));
        arguments.get(2).setBytes(java.util.Arrays.copyOfRange(token, 2, 4));
        arguments.get(3).setBytes(new byte[] {0, (byte) ((token[4] >> 6) & 3)});
        arguments.get(4).setBytes(new byte[] {0, (byte) ((token[4] >> 3) & 7)});
        arguments.get(5).setBytes(new byte[] {0, (byte) (token[4] & 7)});
        arguments.get(6).setBytes(java.util.Arrays.copyOfRange(token, 5, 8));
        arguments.get(7).setBytes(java.util.Arrays.copyOfRange(token, 8, 12));
        requireLength(arguments.get(8), TOKEN_LENGTH, "CEEDCOD fc")
                .setBytes(new byte[TOKEN_LENGTH]);
    }

    private static int half(DataView area) {
        byte[] bytes = area.toByteArray();
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
    }

    /**
     * CEEDAYS (input_char_date, picture_string, output_Lilian_date, fc)。
     *
     * <p>絵文字列は YYYY、MM、DD と、英字でない区切りの文字だけを受ける。区切りは入力の同じ位置に同じ文字を求める。
     * ほかの絵 (Mmm、YY、era など) と、VSTRING の長さが渡された域を越える形は、LE の扱いを確かめていないので失敗させる。
     * 失敗の feedback では output_Lilian_date を 0 にする (頁による)。
     */
    static void ceedays(CodePage codePage, List<DataView> arguments) {
        requireArguments(arguments, 4, "CEEDAYS");
        String date = vstring(codePage, arguments.get(0), "CEEDAYS input_char_date");
        String picture = vstring(codePage, arguments.get(1), "CEEDAYS picture_string");
        DataView output = requireLength(arguments.get(2), Integer.BYTES, "CEEDAYS output_Lilian_date");
        DataView fc = requireLength(arguments.get(3), TOKEN_LENGTH, "CEEDAYS fc");
        int lilian = 0;
        Feedback feedback = parse(date, picture);
        if (feedback == null) {
            lilian = (int) (lilianDate(date, picture) - LILIAN_ORIGIN);
            feedback = CEE000;
        }
        output.setBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(lilian).array());
        token(codePage, fc, feedback);
    }

    /**
     * CEELOCT (output_Lilian, output_seconds, output_Gregorian, fc)。
     *
     * <p>地方時は実行単位の時計 ({@code ProgramContext.clock()}) の時間帯である。秒は 1582 年 10 月 14 日 00:00:00 からの
     * 秒 (閏秒を数えない) を COMP-2 で、Gregorian は YYYYMMDDHHMISS999 の 17 文字で置く。
     */
    static void ceeloct(CodePage codePage, Clock clock, List<DataView> arguments) {
        requireArguments(arguments, 4, "CEELOCT");
        DataView lilianArea = requireLength(arguments.get(0), Integer.BYTES, "CEELOCT output_Lilian");
        DataView secondsArea = requireLength(arguments.get(1), Long.BYTES, "CEELOCT output_seconds");
        DataView gregorianArea = requireLength(arguments.get(2), 17, "CEELOCT output_Gregorian");
        DataView fc = requireLength(arguments.get(3), TOKEN_LENGTH, "CEELOCT fc");
        LocalDateTime now = LocalDateTime.now(Objects.requireNonNull(clock, "clock"));
        long lilian = now.toLocalDate().toEpochDay() - LILIAN_ORIGIN;
        int millis = now.getNano() / 1_000_000;
        lilianArea.setBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt((int) lilian).array());
        BigDecimal seconds = BigDecimal.valueOf(lilian * 86_400L + now.toLocalTime().toSecondOfDay())
                .add(BigDecimal.valueOf(millis, 3));
        Ops.storeFloat(Decimal.parse(seconds.toPlainString()), secondsArea.storage(), secondsArea.offset(), Long.BYTES);
        gregorianArea.setBytes(codePage.encode(String.format("%04d%02d%02d%02d%02d%02d%03d", now.getYear(),
                now.getMonthValue(), now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond(), millis)));
        token(codePage, fc, CEE000);
    }

    /** 入力を絵に当てて誤りの feedback を返す。正しければ null。 */
    private static Feedback parse(String date, String picture) {
        int year = -1;
        int month = -1;
        int day = -1;
        if (date.length() < picture.length()) {
            return CEE2EB;
        }
        if (date.length() > picture.length()) {
            throw new IllegalArgumentException("CEEDAYS input longer than the picture string is not verified");
        }
        boolean numeric = true;
        for (int i = 0; i < picture.length(); ) {
            int width;
            if (picture.startsWith("YYYY", i)) {
                width = 4;
            } else if (picture.startsWith("MM", i) || picture.startsWith("DD", i)) {
                width = 2;
            } else if (Character.isLetter(picture.charAt(i))) {
                throw new IllegalArgumentException("CEEDAYS picture string is not supported: " + picture);
            } else {
                if (date.charAt(i) != picture.charAt(i)) {
                    throw new IllegalArgumentException("CEEDAYS separator mismatch is not verified: " + date);
                }
                i++;
                continue;
            }
            String field = date.substring(i, i + width);
            if (!field.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                numeric = false;
            } else if (width == 4) {
                year = Integer.parseInt(field);
            } else if (picture.charAt(i) == 'M') {
                month = Integer.parseInt(field);
            } else {
                day = Integer.parseInt(field);
            }
            i += width;
        }
        if (!picture.contains("YYYY") || !picture.contains("MM") || !picture.contains("DD")) {
            throw new IllegalArgumentException("CEEDAYS picture string without YYYY, MM and DD is not supported: "
                    + picture);
        }
        if (!numeric) {
            return CEE2EO;
        }
        if (month < 1 || month > 12) {
            return CEE2EL;
        }
        if (day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
            return CEE2EC;
        }
        LocalDate value = LocalDate.of(year, month, day);
        return value.isBefore(FIRST_DATE) ? CEE2EH : null;
    }

    private static long lilianDate(String date, String picture) {
        int year = Integer.parseInt(date.substring(picture.indexOf("YYYY"), picture.indexOf("YYYY") + 4));
        int month = Integer.parseInt(date.substring(picture.indexOf("MM"), picture.indexOf("MM") + 2));
        int day = Integer.parseInt(date.substring(picture.indexOf("DD"), picture.indexOf("DD") + 2));
        try {
            return LocalDate.of(year, month, day).toEpochDay();
        } catch (DateTimeException invalid) {
            throw new IllegalStateException("date was validated: " + date, invalid);
        }
    }

    /** 半語の長さを前に置いた文字列 (VSTRING)。長さが渡された域を越えれば失敗させる。 */
    private static String vstring(CodePage codePage, DataView area, String parameter) {
        byte[] bytes = area.toByteArray();
        if (bytes.length < Short.BYTES) {
            throw new IllegalArgumentException(parameter + " must be a halfword length-prefixed string");
        }
        int length = (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
        if (length < 0 || length > bytes.length - Short.BYTES) {
            throw new IllegalArgumentException(parameter + " length " + length + " exceeds the passed area of "
                    + (bytes.length - Short.BYTES) + " bytes; what LE reads beyond it is not verified");
        }
        return codePage.decode(java.util.Arrays.copyOfRange(bytes, Short.BYTES, Short.BYTES + length));
    }

    private static void token(CodePage codePage, DataView fc, Feedback feedback) {
        byte[] token = new byte[TOKEN_LENGTH];
        if (feedback.severity() != 0 || feedback.messageNumber() != 0) {
            token[0] = (byte) (feedback.severity() >>> 8);
            token[1] = (byte) feedback.severity();
            token[2] = (byte) (feedback.messageNumber() >>> 8);
            token[3] = (byte) feedback.messageNumber();
            token[4] = 1;
            System.arraycopy(codePage.encode("CEE"), 0, token, 5, 3);
        }
        fc.setBytes(token);
    }

    private static void requireArguments(List<DataView> arguments, int count, String service) {
        // List.of の contains(null) は例外を投げるので、1 つずつ見る
        if (arguments.size() != count || arguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(service + " requires " + count
                    + " arguments; OMITTED is not supported");
        }
    }

    private static DataView requireLength(DataView area, int length, String parameter) {
        if (area.length() != length) {
            throw new IllegalArgumentException(parameter + " must be " + length + " bytes: " + area.length());
        }
        return area;
    }
}
