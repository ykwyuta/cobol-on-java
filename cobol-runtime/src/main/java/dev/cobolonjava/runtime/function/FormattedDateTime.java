package dev.cobolonjava.runtime.function;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** COBOL の日付・時刻形式を組み立て、同じ形式で入力を検査する。 */
public final class FormattedDateTime {
    private static final LocalDate EPOCH = LocalDate.of(1600, 12, 31);
    private static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    private enum Field {
        YEAR("YYYY", true), ORDINAL("DDD", true), DAY("DD", true), MONTH("MM", true),
        WEEK("ww", true), WEEKDAY("D", true), HOUR("hh", false), MINUTE("mm", false),
        SECOND("ss", false), FRACTION("", false);

        final String spelling;
        final boolean date;

        Field(String spelling, boolean date) {
            this.spelling = spelling;
            this.date = date;
        }
    }

    private record Token(Field field, String text) {
    }

    private record Value(BigInteger number, int position, int width) {
    }

    private record Parsed(EnumMap<Field, Value> values, int error) {
        int get(Field field, int fallback) {
            Value value = values.get(field);
            return value == null ? fallback : value.number().intValueExact();
        }
    }

    private FormattedDateTime() {
    }

    public static byte[] format(ProgramContext context, CodePage page, String function,
                                byte[] format, Decimal first, Decimal second) {
        LocalDate date = null;
        BigDecimal seconds = null;
        switch (function) {
            case "FORMATTED-CURRENT-DATE" -> {
                ZonedDateTime now = ZonedDateTime.now(context.clock());
                date = now.toLocalDate();
                seconds = BigDecimal.valueOf(now.toLocalTime().toNanoOfDay(), 9);
            }
            case "FORMATTED-DATE" -> date = integerDate(first);
            case "FORMATTED-TIME" -> seconds = first.toBigDecimal();
            case "FORMATTED-DATETIME" -> {
                date = integerDate(first);
                seconds = second.toBigDecimal();
            }
            default -> throw new IllegalArgumentException(function);
        }
        List<Token> tokens = tokens(page.decode(format).stripTrailing());
        boolean weekly = tokens.stream().anyMatch(token -> token.field() == Field.WEEK);
        boolean combined = date != null && seconds != null;
        if (combined && tokens.stream().noneMatch(token -> token.field() == null && token.text().equals("T"))) {
            throw new IllegalArgumentException(function + " requires a combined date and time format");
        }
        if (seconds != null && (seconds.signum() < 0 || seconds.compareTo(SECONDS_PER_DAY) >= 0)) {
            throw new IllegalArgumentException("seconds outside a single day");
        }
        int whole = seconds == null ? 0 : seconds.intValue();
        StringBuilder result = new StringBuilder();
        boolean datePart = date != null;
        for (Token token : tokens) {
            Field field = token.field();
            if (field == null) {
                result.append(token.text());
                if (combined && token.text().equals("T")) datePart = false;
                continue;
            }
            if (field.date != datePart) {
                throw new IllegalArgumentException("field outside its date or time part: " + token.text());
            }
            int width = token.text().length();
            if (field == Field.FRACTION) {
                BigInteger fraction = seconds.remainder(BigDecimal.ONE).movePointRight(width).toBigInteger();
                result.append(String.format(Locale.ROOT, "%0" + width + "d", fraction));
                continue;
            }
            int number = switch (field) {
                case YEAR -> weekly ? date.get(IsoFields.WEEK_BASED_YEAR) : date.getYear();
                case MONTH -> date.getMonthValue();
                case DAY -> date.getDayOfMonth();
                case ORDINAL -> date.getDayOfYear();
                case WEEK -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                case WEEKDAY -> date.getDayOfWeek().getValue();
                case HOUR -> whole / 3600;
                case MINUTE -> whole / 60 % 60;
                case SECOND -> whole % 60;
                case FRACTION -> throw new IllegalStateException();
            };
            result.append(String.format(Locale.ROOT, "%0" + width + "d", number));
        }
        return page.encode(result.toString());
    }

    public static Decimal parse(CodePage page, String function, byte[] format, byte[] input) {
        Parsed parsed = parse(tokens(page.decode(format).stripTrailing()), page.decode(input));
        if (function.equals("TEST-FORMATTED-DATETIME")) return Decimal.of(parsed.error(), 0);
        if (parsed.error() != 0) {
            throw new IllegalArgumentException(function + ": invalid text at character " + parsed.error());
        }
        if (function.equals("INTEGER-OF-FORMATTED-DATE")) {
            int year = parsed.get(Field.YEAR, 0);
            if (year == 0) throw new IllegalArgumentException(function + " requires a year");
            LocalDate date;
            if (parsed.values().containsKey(Field.WEEK)) {
                LocalDate jan4 = LocalDate.of(year, 1, 4);
                date = jan4.minusDays(jan4.getDayOfWeek().getValue() - 1L)
                        .plusWeeks(parsed.get(Field.WEEK, 1) - 1L)
                        .plusDays(parsed.get(Field.WEEKDAY, 1) - 1L);
            } else if (parsed.values().containsKey(Field.ORDINAL)) {
                date = LocalDate.ofYearDay(year, parsed.get(Field.ORDINAL, 1));
            } else {
                date = LocalDate.of(year, parsed.get(Field.MONTH, 1), parsed.get(Field.DAY, 1));
            }
            return Decimal.of(ChronoUnit.DAYS.between(EPOCH, date), 0);
        }
        int whole = parsed.get(Field.HOUR, 0) * 3600 + parsed.get(Field.MINUTE, 0) * 60
                + parsed.get(Field.SECOND, 0);
        Value fraction = parsed.values().get(Field.FRACTION);
        return fraction == null ? Decimal.of(whole, 0)
                : Decimal.of(BigInteger.valueOf(whole).multiply(BigInteger.TEN.pow(fraction.width()))
                        .add(fraction.number()), fraction.width());
    }

    private static List<Token> tokens(String format) {
        List<Token> tokens = new ArrayList<>();
        for (int at = 0; at < format.length();) {
            char c = format.charAt(at);
            Field found = null;
            String text = null;
            boolean fraction = c == 's' && (at > 0 && ".,s".indexOf(format.charAt(at - 1)) >= 0
                    || !format.startsWith("ss", at));
            if (fraction) {
                int end = at + 1;
                while (end < format.length() && format.charAt(end) == 's') end++;
                text = format.substring(at, end);
                found = Field.FRACTION;
            } else {
                for (Field field : Field.values()) {
                    if (field != Field.FRACTION && format.startsWith(field.spelling, at)) {
                        found = field;
                        text = field.spelling;
                        break;
                    }
                }
            }
            if (text == null) {
                if ("-:TW.,Z".indexOf(c) < 0) {
                    throw new IllegalArgumentException("unsupported date/time format: " + format);
                }
                text = Character.toString(c);
            }
            tokens.add(new Token(found, text));
            at += text.length();
        }
        if (tokens.isEmpty()) throw new IllegalArgumentException("empty date/time format");
        return tokens;
    }

    private static Parsed parse(List<Token> tokens, String text) {
        EnumMap<Field, Value> values = new EnumMap<>(Field.class);
        int at = 0;
        for (Token token : tokens) {
            int width = token.text().length();
            if (at + width > text.length()) return new Parsed(values, text.length() + 1);
            if (token.field() == null) {
                if (text.charAt(at) != token.text().charAt(0)) return new Parsed(values, at + 1);
            } else {
                for (int i = at; i < at + width; i++) {
                    if (text.charAt(i) < '0' || text.charAt(i) > '9') return new Parsed(values, i + 1);
                }
                values.putIfAbsent(token.field(), new Value(
                        new BigInteger(text.substring(at, at + width)), at + 1, width));
            }
            at += width;
        }
        if (at != text.length()) return new Parsed(values, at + 1);
        Parsed parsed = new Parsed(values, 0);
        int year = parsed.get(Field.YEAR, 2000);
        int month = parsed.get(Field.MONTH, 1);
        for (Field field : List.of(Field.YEAR, Field.MONTH, Field.DAY, Field.ORDINAL, Field.WEEK,
                Field.WEEKDAY, Field.HOUR, Field.MINUTE, Field.SECOND)) {
            Value value = values.get(field);
            if (value == null) continue;
            int lower = switch (field) {
                case YEAR -> 1601;
                case HOUR, MINUTE, SECOND -> 0;
                default -> 1;
            };
            int upper = switch (field) {
                case YEAR -> 9999;
                case MONTH -> 12;
                case DAY -> YearMonth.of(year, month).lengthOfMonth();
                case ORDINAL -> Year.of(year).length();
                case WEEK -> LocalDate.of(year, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                case WEEKDAY -> 7;
                case HOUR -> 23;
                default -> 59;
            };
            int number = value.number().intValueExact();
            if (number < lower || number > upper) return new Parsed(values, value.position());
        }
        return parsed;
    }

    private static LocalDate integerDate(Decimal value) {
        long days = value.toBigDecimal().setScale(0, RoundingMode.DOWN).longValueExact();
        if (days < 1 || days > ChronoUnit.DAYS.between(EPOCH, LAST_DATE)) {
            throw new IllegalArgumentException("integer date outside supported range");
        }
        return EPOCH.plusDays(days);
    }
}
