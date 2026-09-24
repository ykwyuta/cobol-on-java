package dev.cobolonjava.oracle.run;

import dev.cobolonjava.oracle.script.HerculesCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hercules を起動してテストケースを実行する (要件 FR-212)。
 *
 * <p>Hercules の所在は環境変数 {@code HERCULES} で指定できる。指定がなければ
 * {@code PATH} 上の {@code hercules} を用いる。見つからない場合 {@link #detect()} は空を返し、
 * オラクルを用いるテストは実行をスキップする。
 *
 * <p>{@code -t} オプションはテストモードを有効にする。これがないと {@code runtest} コマンドが
 * 受理されない。
 */
public final class HerculesRunner {

    /** Hercules 自身の実行時間の上限 (秒)。テストが停止しない場合の保険。 */
    private static final long PROCESS_TIMEOUT_SECONDS = 60;

    /** 重大度 E / S の Hercules メッセージ。正常な実行では現れない。 */
    private static final Pattern CONSOLE_ERROR = Pattern.compile("HHC[0-9]{5}[ES] ");

    private static final Pattern WAIT_PSW = Pattern.compile(
            "disabled wait state\\s+([0-9A-Fa-f]+)\\s+([0-9A-Fa-f]+)");

    private final Path hercules;
    private final Path workDir;

    public HerculesRunner(Path hercules, Path workDir) {
        this.hercules = hercules;
        this.workDir = workDir;
    }

    /**
     * オラクルが使えるかどうかの判定結果。
     *
     * <p>使えない場合は理由を伴う。理由を握りつぶさないのは、
     * 「オラクルがないので検証をスキップした」と「間違ったオラクルで検証したつもりになった」を
     * 取り違えないためである。
     */
    public sealed interface Detection {
        record Available(HerculesRunner runner) implements Detection {
        }

        record Unavailable(String reason) implements Detection {
        }
    }

    /** 実行可能な Hercules を探す。 */
    static Optional<Path> findHercules() {
        String configured = System.getenv("HERCULES");
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            return Files.isExecutable(p) ? Optional.of(windowsCli(p)) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (isWindows()) {
                Path cli = Path.of(dir, "herclin.exe");
                if (Files.isExecutable(cli)) {
                    return Optional.of(cli);
                }
            }
            Path p = Path.of(dir, "hercules");
            if (Files.isExecutable(p)) {
                return Optional.of(windowsCli(p));
            }
            if (isWindows()) {
                Path windows = Path.of(dir, "hercules.exe");
                if (Files.isExecutable(windows)) {
                    return Optional.of(windowsCli(windows));
                }
            }
        }
        return Optional.empty();
    }

    private static Path windowsCli(Path executable) {
        if (isWindows() && executable.getFileName().toString().equalsIgnoreCase("hercules.exe")) {
            Path cli = executable.resolveSibling("herclin.exe");
            if (Files.isExecutable(cli)) {
                return cli;
            }
        }
        return executable;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    /**
     * オラクルとして使える Hercules を探す。
     *
     * <p><b>バージョン番号ではなく能力を検査する。</b>本処理系が必要とするのは
     * {@code -r} によるスクリプト実行であり、これは SDL Hyperion 4.x で導入された。
     * 旧来の Hercules 3.x は {@code -r} を持たないため、{@code PATH} 上にそれがあると
     * 起動には成功しながらスクリプトが一切実行されず、<b>記憶域を読み出せないまま
     * 「検証した」ことになってしまう</b>。これを防ぐため、使う前に能力を確かめる。
     */
    public static Detection detect(Path workDir) {
        Optional<Path> found = findHercules();
        if (found.isEmpty()) {
            return new Detection.Unavailable(
                    "Hercules が見つからない。環境変数 HERCULES で実行ファイルを指定するか PATH に置くこと");
        }
        Path hercules = found.get();
        String help = readHelp(hercules);
        if (help == null) {
            return new Detection.Unavailable(hercules + " の起動確認に失敗した");
        }
        if (!help.contains("-r ")) {
            return new Detection.Unavailable(hercules
                    + " は -r (スクリプト実行) に対応していない。SDL Hyperion 4.x が必要である。"
                    + " 環境変数 HERCULES で対応版を指定すること");
        }
        return new Detection.Available(new HerculesRunner(hercules, workDir));
    }

    /** {@code --help} の出力を読む。取得できなければ null。 */
    private static String readHelp(Path hercules) {
        try {
            ProcessBuilder pb = new ProcessBuilder(hercules.toString(), "--help");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    public HerculesResult run(HerculesCase testCase) throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path config = workDir.resolve("tests.conf");
        // コンソール装置がサブチャネル 0 にある最小構成
        Files.writeString(config, "0009  1052-C /\n", StandardCharsets.UTF_8);

        Path script = workDir.resolve(testCase.name() + ".tst");
        // スクリプトの末尾に exit を置かないと Hercules が終了しない
        Files.writeString(script, testCase.toScript() + "exit\n", StandardCharsets.UTF_8);

        Path logFile = workDir.resolve(testCase.name() + ".log");
        // Windows の NoUI モードでは -r のスクリプト内の exit のあとも
        // 標準入力側のスクリプトが始まるため、そこにも終了指示を渡す。
        Path input = workDir.resolve(testCase.name() + ".stdin");
        Files.writeString(input, "exit\n", StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(List.of(
                hercules.toString(),
                "-f", config.toString(),
                "-r", script.toString(),
                "-n",       // パネルを使わない
                "-t2.0"));  // テストモードを有効にする ($runtest / runtest が使えるようになる)
        // 命令試験に Rexx は不要。未導入の Windows 版が起動時に E メッセージを出さないようにする。
        pb.environment().put("HREXX_PACKAGE", "none");
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(input.toFile()));
        Process process = pb.start();
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Hercules did not terminate within " + PROCESS_TIMEOUT_SECONDS
                    + " seconds for case " + testCase.name());
        }

        // Windows 版のコンソールはローカルコードページの非 ASCII バイトを混ぜる。
        // 解析対象の HHC メッセージと 16 進ダンプは ASCII なので、破損バイトは置換する。
        String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        List<String> errors = consoleErrors(log);
        if (!errors.isEmpty()) {
            // スクリプトが拒否されても Hercules は実行を続け、記憶域は初期値のまま残る。
            // それに気付かずに採取すると、ゼロで埋まった領域を「実機の結果」として
            // 信じてしまう。オラクルとして致命的なので、ここで必ず止める。
            throw new IOException("Hercules がコマンドを拒否した (case " + testCase.name() + "):\n"
                    + String.join("\n", errors));
        }
        return new HerculesResult(log, StorageDump.parse(log), parseWaitPswAddress(log));
    }

    /**
     * 停止時の待機 PSW の命令アドレスを取り出す。
     * z/Arch の PSW は 16 バイトで、後半 8 バイトが命令アドレスである。
     */
    /**
     * コンソール出力に現れた重大度 E (エラー) または S (重大) のメッセージを集める。
     *
     * <p>正常な実行ではこれらは 1 行も現れない。現れたということはスクリプトのどこかが
     * 受理されなかったということであり、そのまま採取を続けると誤った期待値を得る。
     */
    static List<String> consoleErrors(String log) {
        List<String> errors = new java.util.ArrayList<>();
        for (String line : log.split("\\R")) {
            // Windows 版のポータブル配布物は Rexx を同梱しない。使用を無効にしても
            // 起動時にこの E メッセージを出すが、命令試験には影響しない。
            if (line.contains("HHC17511E REXX() Could not enable default Rexx package")) {
                continue;
            }
            if (CONSOLE_ERROR.matcher(line).find()) {
                errors.add(line.trim());
            }
        }
        return errors;
    }

    static long parseWaitPswAddress(String log) {
        Matcher m = WAIT_PSW.matcher(log);
        long address = -1;
        while (m.find()) {
            address = Long.parseLong(m.group(2), 16);
        }
        return address;
    }
}
