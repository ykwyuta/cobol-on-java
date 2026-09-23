package dev.cobolonjava.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 利用者のプロジェクトの標準ディレクトリ体系 (設計 91 §2)。
 *
 * <p>Maven の {@code src/main/java} に倣い、<b>言語と役割ごとに 1 つの置き場</b>を持つ。
 * ホストでは原文・写し句・手続きがそれぞれ別の区分データセット (ライブラリ) に入っている。
 * その 1 ライブラリを 1 ディレクトリへ写す。
 *
 * <pre>
 * src/main/cobol        COBOL の原文        (SYSIN / SRCLIB)
 * src/main/copybook     COPY の写し句       (SYSLIB)
 * src/main/bms          BMS の mapset       (記号マップを翻訳時に作り、原文を classpath に載せる)
 * src/main/pli          PL/I の原文
 * src/main/pli-include  %INCLUDE のメンバ
 * src/main/asm          HLASM の原文 (.asm / .hlasm)
 * src/main/jcl          ジョブ記述 (.jcl と宣言的形式 .job)
 * src/main/proclib      目録手続きと JCL の INCLUDE メンバ (PROCLIB / JCLLIB)
 * </pre>
 */
public record SourceLayout(Path cobol, List<Path> copybooks, Path bms, Path pli,
                           List<Path> pliIncludes, Path hlasm, Path jcl, Path proclib) {

    /** COBOL の原文として拾う拡張子。写し句 ({@code .cpy}) は拾わない。 */
    static final Set<String> COBOL_SUFFIXES = Set.of("cbl", "cob", "cobol");
    static final Set<String> PLI_SUFFIXES = Set.of("pli", "pl1");
    static final Set<String> BMS_SUFFIXES = Set.of("bms");
    static final Set<String> HLASM_SUFFIXES = Set.of("asm", "hlasm");
    /** {@code .jcl} は JCL、{@code .job} は宣言的形式として読む (要件 FR-132)。 */
    static final Set<String> JOB_SUFFIXES = Set.of("jcl", "job");

    public SourceLayout {
        copybooks = List.copyOf(copybooks);
        pliIncludes = List.copyOf(pliIncludes);
    }

    /** {@code basedir} の下の標準の置き場。 */
    public static SourceLayout standard(Path basedir) {
        Path main = basedir.resolve("src/main");
        return new SourceLayout(main.resolve("cobol"), List.of(main.resolve("copybook")),
                main.resolve("bms"), main.resolve("pli"), List.of(main.resolve("pli-include")),
                main.resolve("asm"), main.resolve("jcl"), main.resolve("proclib"));
    }

    /**
     * 写し句を探す置き場の並び。BMS の置き場を<b>最後に</b>足す。
     *
     * <p>{@code COPY TODOSET.} は、同じ名前の写し句が無ければ {@code TODOSET.bms} から作った
     * 記号マップになる。手書きの写し句を置けば、そちらが勝つ (ホストで生成済みの記号マップを
     * SYSLIB に置いた資産と同じ)。
     */
    List<Path> copybookSearchPath() {
        List<Path> path = new ArrayList<>(copybooks);
        path.add(bms);
        return path.stream().filter(Files::isDirectory).toList();
    }

    List<Path> pliIncludeSearchPath() {
        return pliIncludes.stream().filter(Files::isDirectory).toList();
    }

    List<Path> cobolSources() throws IOException {
        return files(cobol, COBOL_SUFFIXES);
    }

    List<Path> pliSources() throws IOException {
        return files(pli, PLI_SUFFIXES);
    }

    List<Path> hlasmSources() throws IOException {
        return files(hlasm, HLASM_SUFFIXES);
    }

    List<Path> bmsSources() throws IOException {
        return files(bms, BMS_SUFFIXES);
    }

    List<Path> jobDescriptions() throws IOException {
        return files(jcl, JOB_SUFFIXES);
    }

    /**
     * 置き場の下を深さを問わず拾い、並びを名前で固定する。
     *
     * <p>下位ディレクトリは<b>整理のためだけ</b>にある。プログラムの名前は {@code PROGRAM-ID}
     * が決め、ディレクトリは関わらない (ホストのロードライブラリに階層が無いのと同じ)。
     * 並びを固定するのは、ファイル体系の列挙順で翻訳の順や診断の順が変わらないようにするためである。
     * 拡張子の大文字小文字は問わない。ホストから落とした資産は {@code .CBL} であることが多い。
     */
    static List<Path> files(Path directory, Set<String> suffixes) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> suffixes.contains(suffix(file)))
                    .sorted()
                    .toList();
        }
    }

    private static String suffix(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
