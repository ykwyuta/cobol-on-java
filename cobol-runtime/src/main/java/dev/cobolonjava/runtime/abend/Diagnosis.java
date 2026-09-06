package dev.cobolonjava.runtime.abend;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.List;

/**
 * 異常終了の診断出力 (要件 FR-142, FR-143)。
 *
 * <p>異常終了コードだけでは、どこで何が起きたのか分からない。ホストの言語環境は
 * {@code CEEDUMP} として、止まった場所・呼び出し履歴・記憶域の中身を書き出す。
 * それが無ければ、本番で一度だけ起きた事故を追えない。
 *
 * <h2>止まった場所はクラスファイルの行番号表から取る</h2>
 * <p>翻訳するとき、文ごとに原文の行をクラスファイルへ埋めてある。だから<b>JVM の呼び出し
 * 履歴がそのまま原文の行を指す</b>。対応表を自分で持たないので、本体とずれる余地がない。
 *
 * <h2>書式はホストのものではない</h2>
 * <p>中身はホストの {@code CEEDUMP} が持つものに揃えたが、<b>並べ方と字面は真似ていない</b>。
 * 真似ると、本物と見分けがつかないものが出てきて、既存の解析道具が誤って読む
 * (暫定判断 P-051)。
 */
public final class Diagnosis {

    private Diagnosis() {
    }

    /** 呼び出し履歴の 1 段。 */
    private record Frame(String program, int line, String source) {
    }

    /**
     * 診断出力を組み立てる。
     *
     * @param code    異常終了コード。分からなければ {@code null}
     * @param failure 抜けてきた誤り
     * @param context 実行時の入口。動いていたプログラムと記憶域を持つ
     * @return 書き出す行。{@code TERMTHDACT(QUIET)} なら空
     */
    public static List<String> of(AbendCode code, Throwable failure, ProgramContext context) {
        DumpLevel level = context == null ? DumpLevel.TRACE : context.dumpLevel();
        List<String> out = new ArrayList<>();
        if (!level.message()) {
            return out;
        }
        out.add(code == null
                ? "CEE3250C AN UNHANDLED CONDITION ENDED THE RUN."
                : "CEE3250C THE SYSTEM OR USER ABEND " + code.text() + " WAS ISSUED.");
        if (code != null) {
            out.add("  CONDITION: " + code.reason());
        }
        String message = failure == null ? null : failure.getMessage();
        if (message != null && !message.isBlank()) {
            out.add("  DETAIL:    " + message);
        }
        if (level.traceback()) {
            out.add("");
            out.addAll(traceback(failure));
        }
        if (level.storage() && context != null) {
            out.addAll(storage(context));
        }
        return out;
    }

    /**
     * 呼び出し履歴。
     *
     * <p>載せるのは<b>翻訳したプログラムの段だけ</b>である。ランタイムの中の段は、
     * COBOL を書いた人にとっては自分の書いたものではない。
     */
    private static List<String> traceback(Throwable failure) {
        List<String> out = new ArrayList<>();
        out.add("TRACEBACK:");
        List<Frame> frames = framesOf(failure);
        if (frames.isEmpty()) {
            out.add("  (no compiled program is on the call chain)");
            return out;
        }
        out.add("  PROGRAM   STATEMENT  SOURCE");
        for (Frame frame : frames) {
            out.add("  " + pad(frame.program(), 10)
                    + pad(frame.line() > 0 ? String.valueOf(frame.line()) : "-", 11)
                    + (frame.source() == null ? "-" : frame.source()));
        }
        return out;
    }

    private static List<Frame> framesOf(Throwable failure) {
        List<Frame> out = new ArrayList<>();
        if (failure == null) {
            return out;
        }
        String prefix = ProgramSupport.GENERATED_PACKAGE + ".";
        String seen = null;
        for (StackTraceElement element : failure.getStackTrace()) {
            if (!element.getClassName().startsWith(prefix)) {
                continue;
            }
            String program = element.getClassName().substring(prefix.length());
            // 同じプログラムの中の分岐 (dispatch や performRange) は 1 段にまとめる。
            // COBOL を書いた人から見れば、それらは実行の仕掛けであって呼び出しではない
            if (program.equals(seen) && element.getLineNumber() <= 0) {
                continue;
            }
            seen = program;
            out.add(new Frame(program, element.getLineNumber(), element.getFileName()));
        }
        return out;
    }

    /** 動いていたプログラムの作業場所。内側から順に並べる。 */
    private static List<String> storage(ProgramContext context) {
        List<String> out = new ArrayList<>();
        for (ProgramContext.Active active : context.active()) {
            out.add("");
            out.add("STORAGE FOR " + active.name() + ":");
            out.addAll(hexDump(active.storage(), context.codePage()));
        }
        return out;
    }

    /** 1 行 16 バイト。左に 16 進、右にコードページで読んだ文字。 */
    private static List<String> hexDump(Storage storage, CodePage codePage) {
        List<String> out = new ArrayList<>();
        byte[] bytes = storage == null ? new byte[0] : storage.array();
        if (bytes.length == 0) {
            out.add("  (no working storage)");
            return out;
        }
        for (int at = 0; at < bytes.length; at += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (i % 4 == 0 && i > 0) {
                    hex.append(' ');
                }
                if (at + i < bytes.length) {
                    hex.append(String.format("%02X", bytes[at + i]));
                    text.append(printable(bytes[at + i], codePage));
                } else {
                    hex.append("  ");
                    text.append(' ');
                }
            }
            out.add(String.format("  %06X  %s  |%s|", at, hex, text));
        }
        return out;
    }

    /**
     * バイト 1 個を読める文字にする。
     *
     * <p>読めないバイトは点にする。ホストのダンプもそうしており、<b>16 進のほうが正</b>で
     * ある。文字のほうは目で追うための添え物である。
     */
    private static char printable(byte value, CodePage codePage) {
        String decoded = codePage.decode(new byte[] {value});
        if (decoded.length() != 1) {
            return '.';
        }
        char c = decoded.charAt(0);
        return c >= 0x20 && c < 0x7F ? c : '.';
    }

    private static String pad(String text, int width) {
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
