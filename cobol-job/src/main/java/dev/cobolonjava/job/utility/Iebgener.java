package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code IEBGENER} (要件 FR-137)。
 *
 * <p>{@code SYSUT1} を {@code SYSUT2} へ<b>そのまま写す</b>。バッチでいちばんよく使われる
 * ユーティリティである。
 *
 * <h2>バイトのまま写す</h2>
 * <p>レコードへ切ってから書き直すのではなく、バイト列と属性をそのまま写す。
 * 切って書き直せば、同じ属性なら同じバイトに戻るはずだが、<b>戻るはずだ</b>という仮定を
 * 挟まずに済む。写しは写しである。
 *
 * <p>レコード様式を変えながら写す使い方 ({@code SYSIN} の制御文) は未対応であり、
 * 制御文が書かれていれば誤りとして報告する (暫定判断 P-047)。
 */
public final class Iebgener extends UtilityProgram {

    /** 写し元。 */
    private static final String SYSUT1 = "SYSUT1";
    /** 写し先。 */
    private static final String SYSUT2 = "SYSUT2";

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        List<String> control = control(context, SYSIN);
        if (!control.isEmpty()) {
            print(context, "IEB000I SYSIN CONTROL STATEMENTS ARE NOT SUPPORTED YET");
            context.setReturnCode(12);
            return;
        }
        Path from = pathOf(context, SYSUT1);
        if (!Files.isReadable(from)) {
            print(context, "IEB000I SYSUT1 NOT FOUND");
            context.setReturnCode(12);
            return;
        }
        Path to = pathOf(context, SYSUT2);
        byte[] bytes = readBytes(from);
        writeBytes(to, bytes);
        DataSetAttributes attributes = DataSetAttributes.read(from);
        attributes.write(to);
        print(context, "IEB147I " + records(bytes, attributes) + " RECORDS COPIED");
        context.setReturnCode(0);
    }

    /** 写したレコードの数。覚え書きに書く。 */
    private static int records(byte[] bytes, DataSetAttributes attributes) {
        return switch (attributes.format()) {
            case FIXED -> attributes.recordLength() <= 0
                    ? 0
                    : (bytes.length + attributes.recordLength() - 1) / attributes.recordLength();
            case VARIABLE -> countVariable(bytes);
            case LINE -> countLines(bytes, attributes);
        };
    }

    private static int countVariable(byte[] bytes) {
        int count = 0;
        int at = 0;
        while (at + 4 <= bytes.length) {
            int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
            if (length < 4 || at + length > bytes.length) {
                break;
            }
            count++;
            at += length;
        }
        return count;
    }

    private static int countLines(byte[] bytes, DataSetAttributes attributes) {
        return lines(bytes, attributes.codePage()).size();
    }
}
