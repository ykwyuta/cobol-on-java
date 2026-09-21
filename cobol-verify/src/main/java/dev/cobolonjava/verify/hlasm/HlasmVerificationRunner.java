package dev.cobolonjava.verify.hlasm;

import dev.cobolonjava.hlasm.Assembler;
import dev.cobolonjava.hlasm.ObjectModule;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.hlasm.HlasmRuntime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * HLASM の外部コーパスを、1 本の故障で測定全体を止めずに組み立て・実行する。
 *
 * <p>測る対象は 2 段である (設計 27 §3)。
 *
 * <ol>
 *   <li><b>組み立て</b> — 原文から作った機械語を、{@code .obj} に置いた期待値と比べる。
 *       違えば {@code WRONG_OBJECT} とし、<b>実行へは進まない</b>。誤った機械語を実行した
 *       結果を「実行の不一致」として数えると、どちらが悪いのか分からなくなる</li>
 *   <li><b>実行</b> — 作業域を渡して動かし、結果を {@code .out} と比べる</li>
 * </ol>
 *
 * <p>期待値は参照実装 (Hercules) から採る。同じ原文を Hercules へ流し、同じ作業域を
 * 同じ番地に置いて、実行後にその範囲を読み出せば {@code .out} になる。
 */
public final class HlasmVerificationRunner {

    /** 1 本にかける時間の限り。返ってこない組み立て・実行を見捨てる。 */
    public static final long LIMIT_SECONDS = 60;

    /** 作業域の大きさ。原文はこれを引数 1 つとして受け取る。 */
    public static final int WORK_AREA_BYTES = 256;

    /**
     * コーパス 1 本。
     *
     * @param name           資材の名前
     * @param group          直下のディレクトリ名。機能区分として数える
     * @param text           原文
     * @param expectedObject 期待する機械語 (16 進)。無ければ {@code null}
     * @param input          作業域の初期値 (16 進)。無ければゼロで埋める
     * @param expectedOutput 期待する実行結果。無ければ実行しない
     */
    public record Source(String name, String group, String text, String expectedObject,
                         String input, String expectedOutput) {
    }

    private final long limitSeconds;

    public HlasmVerificationRunner(long limitSeconds) {
        this.limitSeconds = limitSeconds;
    }

    public static HlasmVerificationRunner standard() {
        return new HlasmVerificationRunner(LIMIT_SECONDS);
    }

    public HlasmCaseOutcome run(Source source) {
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        Thread worker = new Thread(() -> {
            try {
                done.offer(runNow(source));
            } catch (RuntimeException | LinkageError | StackOverflowError | AssertionError failure) {
                done.offer(failure);
            }
        }, "hlasm-verify-" + source.name());
        worker.setDaemon(true);
        worker.start();
        try {
            Object value = done.poll(limitSeconds, TimeUnit.SECONDS);
            if (value == null) {
                worker.interrupt();
                return HlasmCaseOutcome.timedOut(source.name(), source.group(), limitSeconds);
            }
            return value instanceof Throwable failure
                    ? HlasmCaseOutcome.crashed(source.name(), source.group(), failure)
                    : (HlasmCaseOutcome) value;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return HlasmCaseOutcome.crashed(source.name(), source.group(), interrupted);
        }
    }

    private HlasmCaseOutcome runNow(Source source) {
        Assembler.Result assembled = Assembler.assemble(source.name(), source.text());
        if (!assembled.succeeded()) {
            return HlasmCaseOutcome.rejected(source.name(), source.group(),
                    assembled.diagnostics().stream().map(Object::toString).toList());
        }
        ObjectModule module = assembled.module();

        boolean objectChecked = source.expectedObject() != null;
        if (objectChecked) {
            String expected = normalizeHex(source.expectedObject());
            String actual = module.hex(0, module.length());
            if (!expected.equals(actual)) {
                // 機械語が違うなら実行しない。誤った機械語を動かした結果を
                // 「実行の不一致」として数えると、どちらが悪いのか分からなくなる
                return HlasmCaseOutcome.wrongObject(source.name(), source.group(), expected, actual);
            }
        }
        if (source.expectedOutput() == null) {
            return HlasmCaseOutcome.assembled(source.name(), source.group(), objectChecked);
        }
        String actual = execute(module, source.input());
        return normalizeLines(actual).equals(normalizeLines(source.expectedOutput()))
                ? HlasmCaseOutcome.passed(source.name(), source.group(), objectChecked)
                : HlasmCaseOutcome.wrongOutput(source.name(), source.group(), objectChecked);
    }

    /**
     * 作業域を引数 1 つとして渡して動かし、観測できるものを並べて返す。
     *
     * <p>並べるのは戻りコードと作業域である。Hercules 側では、同じ作業域の範囲を
     * {@code r 番地.長さ} で読み出せば同じものが採れる。
     */
    private String execute(ObjectModule module, String input) {
        byte[] work = new byte[WORK_AREA_BYTES];
        if (input != null) {
            byte[] initial = HexFormat.of().parseHex(normalizeHex(input));
            System.arraycopy(initial, 0, work, 0, Math.min(initial.length, work.length));
        }
        Storage storage = Storage.wrap(work);
        DataView view = storage.view(0, work.length);
        int returnCode;
        try {
            returnCode = HlasmRuntime.execute(module, null, new DataView[] {view});
        } catch (HlasmRuntime.HlasmExecutionException failure) {
            // 異常終了も観測できる結果である。Hercules では待機 PSW の命令アドレスで分かる
            return "ABEND " + abendOf(failure) + "\n";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("RC=%08X%n", returnCode));
        for (int at = 0; at < work.length; at += 16) {
            out.append(String.format("%04X  %s%n", at,
                    HexFormat.of().withUpperCase().formatHex(work, at, at + 16)));
        }
        return out.toString();
    }

    /** 異常終了の完了コード ({@code S0C7} など) を取り出す。 */
    private static String abendOf(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        int at = message.indexOf("S0C");
        return at < 0 ? "UNKNOWN" : message.substring(at, Math.min(at + 4, message.length()));
    }

    public HlasmVerificationReport run(List<Source> sources) {
        List<HlasmCaseOutcome> outcomes = new ArrayList<>();
        for (Source source : sources) {
            outcomes.add(run(source));
        }
        return new HlasmVerificationReport(outcomes);
    }

    /** 16 進は空白と改行を落とし、大文字に揃えてから比べる。桁と値は変えない。 */
    private static String normalizeHex(String text) {
        return text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** 改行だけを LF に揃える。空白、桁、符号は変えない。 */
    private static String normalizeLines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
