package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ブラウザが送った form を中立の {@link BmsTerminalInput} へ変える (設計 77 §4.5.4)。
 *
 * <p>ここでは形だけを確かめる。field が画面にあるか、保護されていないか、長さに収まるかは
 * RECEIVE MAP の {@code BmsInputDecoder} が直前の画面と照合して再検証する。client の hidden / disabled の
 * 状態は信用しない。
 */
public final class BmsTerminalInputBinder {

    /** {@code bms.NAME.occurrence}。名前は BMS の field 名の文字に限る。 */
    private static final Pattern FIELD = Pattern.compile("bms\\.([A-Za-z0-9@#$]{1,30})\\.(\\d{1,4})");
    private static final int MAX_FIELDS = 1920;
    private static final int MAX_VALUE_LENGTH = 1920;

    public BmsTerminalInput bind(String aid, String cursor, Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        BmsAid key = aidOf(aid);
        int cursorOffset = cursorOf(cursor);
        List<BmsTerminalInput.FieldInput> fields = new ArrayList<>();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!parameter.getKey().startsWith("bms.")) {
                continue;
            }
            Matcher matcher = FIELD.matcher(parameter.getKey());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("malformed BMS field parameter: " + parameter.getKey());
            }
            String value = Objects.requireNonNull(parameter.getValue(), "field value");
            if (value.length() > MAX_VALUE_LENGTH || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("BMS field value is too long or contains controls: "
                        + parameter.getKey());
            }
            int occurrence = Integer.parseInt(matcher.group(2));
            if (occurrence < 1) {
                throw new IllegalArgumentException("BMS field occurrence is 1-based: " + parameter.getKey());
            }
            fields.add(new BmsTerminalInput.FieldInput(matcher.group(1).toUpperCase(Locale.ROOT), occurrence, value));
            if (fields.size() > MAX_FIELDS) {
                throw new IllegalArgumentException("too many BMS fields");
            }
        }
        // CLEAR と PA キーは field を送らない (3270 の short read)。送ってきても使わない
        if (key == BmsAid.CLEAR || key == BmsAid.PA1 || key == BmsAid.PA2 || key == BmsAid.PA3) {
            fields.clear();
        }
        return new BmsTerminalInput(key, cursorOffset, fields);
    }

    private static BmsAid aidOf(String aid) {
        if (aid == null || aid.isBlank()) {
            throw new IllegalArgumentException("AID is required");
        }
        String normalized = aid.strip().toUpperCase(Locale.ROOT);
        for (BmsAid candidate : BmsAid.values()) {
            if (candidate.name().equals(normalized) || candidate.cobolName().equals(normalized)) {
                if (candidate == BmsAid.NULL) {
                    break;
                }
                return candidate;
            }
        }
        throw new IllegalArgumentException("unsupported AID: " + normalized);
    }

    private static int cursorOf(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return -1;
        }
        if (!cursor.strip().matches("-?\\d{1,5}")) {
            throw new IllegalArgumentException("malformed cursor position");
        }
        int value = Integer.parseInt(cursor.strip());
        if (value < -1) {
            throw new IllegalArgumentException("cursor position must be -1 or greater");
        }
        return value;
    }
}
