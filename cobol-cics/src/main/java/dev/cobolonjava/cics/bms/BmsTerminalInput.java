package dev.cobolonjava.cics.bms;

import java.util.List;
import java.util.Objects;

/**
 * 端末から届いた入力 (設計 77 §4.5.4、設計 79 §8.2)。
 *
 * <p>adapter が要求から作る中立な形であり、ブラウザの状態を信用した値ではない。
 * {@link BmsInputDecoder} が直前の画面と照合して再検証する。
 *
 * @param cursorOffset 画面先頭からの cursor 位置。分からなければ -1
 * @param fields       利用者が変更した field。消去した field は空文字列で表す
 */
public record BmsTerminalInput(BmsAid aid, int cursorOffset, List<BmsTerminalInput.FieldInput> fields) {

    /** 変更された field 1 回分。 */
    public record FieldInput(String name, int occurrence, String value) {

        public FieldInput {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence is 1-based");
            }
        }
    }

    public BmsTerminalInput {
        Objects.requireNonNull(aid, "aid");
        fields = List.copyOf(fields);
    }

    /**
     * 端末の大文字変換 (UCTRAN) を当てた入力。
     *
     * <p>変えるのは英小文字 a〜z だけである。code page ごとの国別文字の変換は突き合わせていないので
     * 触らない (暫定判断 P-130)。
     */
    public BmsTerminalInput uppercased() {
        List<FieldInput> translated = fields.stream().map(field -> {
            StringBuilder out = new StringBuilder(field.value().length());
            field.value().chars().forEach(c -> out.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : (char) c));
            return new FieldInput(field.name(), field.occurrence(), out.toString());
        }).toList();
        return new BmsTerminalInput(aid, cursorOffset, translated);
    }
}
