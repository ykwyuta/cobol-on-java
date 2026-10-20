package dev.cobolonjava.verify.suite;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.DirectoryCopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.hlasm.HlasmCompiler;
import dev.cobolonjava.pli.PliCompiler;
import dev.cobolonjava.pli.PliPreprocessor;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * スイートの言語ごとの翻訳 (設計 87)。翻訳したものはどれも {@code CobolProgram} のクラスになるので、
 * 動かす段と報告を読む段は言語に依らない。
 */
public interface SuiteLanguage {

    /** 原文の拡張子 (大文字)。 */
    String extension();

    /** 1 本を翻訳する。写し句やマクロはスイートの {@code copy/} から引く。 */
    Translation translate(Path suite, Path source, SuiteManifest.Program program);

    /** 翻訳したクラス 1 つ。 */
    record Unit(String className, byte[] classFile) {
    }

    /**
     * 翻訳の結末。
     *
     * @param units     作ったクラス。1 つ目が主プログラムである
     * @param signature 主プログラムの引数の形。分からなければ null
     * @param errors    誤りの診断。止まった原文の行を持つ。空なら翻訳が通った
     */
    record Translation(List<Unit> units, ProgramSignature signature, List<String> errors) {

        public boolean succeeded() {
            return errors.isEmpty();
        }
    }

    /** COBOL (ZCVS)。CCSID は翻訳時オプション CODEPAGE として渡す。 */
    SuiteLanguage COBOL = new SuiteLanguage() {
        @Override
        public String extension() {
            return "CBL";
        }

        @Override
        public Translation translate(Path suite, Path source, SuiteManifest.Program program) {
            CopyBookResolver copies = new DirectoryCopyBookResolver(suite.resolve("copy"));
            CopyBookResolver alternate = new DirectoryCopyBookResolver(suite.resolve("copyalt"));
            // COPY X OF COPYALT は copyalt の下から引く (ZX103A)。Zeus も同じ名前の置き場を作って流す
            CopyBookResolver resolver = (name, library) -> library != null && library.equalsIgnoreCase("COPYALT")
                    ? alternate.resolve(name, null)
                    : copies.resolve(name, null);
            CobolCompiler compiler = new CobolCompiler(Preprocessor.with(resolver));
            if (program.ccsid() >= 0) {
                // 翻訳時のコードページは定数の符号化を決める。こちらが受けない CCSID なら翻訳系が断る
                compiler = compiler.withOptions(new CompilerOptions(Map.of("CODEPAGE",
                        String.valueOf(program.ccsid()))));
            }
            CobolCompiler.Result result = compiler.compile(source.getFileName().toString(), read(source));
            if (!result.succeeded()) {
                return new Translation(List.of(), null, result.diagnostics().stream()
                        .filter(diagnostic -> !diagnostic.isWarning()).map(Object::toString).toList());
            }
            // 副入口の名前で呼ばれるクラスも同じところへ置く (暫定判断 P-206)
            return new Translation(result.programs().stream()
                    .flatMap(compiled -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(new Unit(compiled.className(), compiled.classFile())),
                            compiled.entryClasses().stream()
                                    .map(entry -> new Unit(entry.className(), entry.classFile()))))
                    .toList(),
                    result.programs().get(0).programSignature(), List.of());
        }
    };

    /** PL/I (ZPVS)。{@code %INCLUDE} はスイートの {@code copy/} の {@code 名前.PLI} から引く。 */
    SuiteLanguage PLI = new SuiteLanguage() {
        @Override
        public String extension() {
            return "PLI";
        }

        @Override
        public Translation translate(Path suite, Path source, SuiteManifest.Program program) {
            PliCompiler compiler = new PliCompiler(new PliPreprocessor(member ->
                    member(suite.resolve("copy"), member, "PLI")));
            PliCompiler.Result result = compiler.compile(source.getFileName().toString(), read(source));
            if (!result.succeeded()) {
                return new Translation(List.of(), null,
                        result.diagnostics().stream().map(Object::toString).toList());
            }
            return new Translation(List.of(new Unit(result.className(), result.classFile())),
                    result.programSignature(), List.of());
        }
    };

    /** HLASM (ZHVS)。マクロと COPY はスイートの {@code copy/} の {@code 名前.mac} から引く。 */
    SuiteLanguage HLASM = new SuiteLanguage() {
        @Override
        public String extension() {
            return "ASM";
        }

        @Override
        public Translation translate(Path suite, Path source, SuiteManifest.Program program) {
            HlasmCompiler compiler = HlasmCompiler.withLibrary(member ->
                    member(suite.resolve("copy"), member, "mac").orElse(null));
            HlasmCompiler.Result result = compiler.compile(source.getFileName().toString(), read(source));
            if (!result.succeeded()) {
                return new Translation(List.of(), null, result.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.severity() == dev.cobolonjava.hlasm.Diagnostic.Severity.ERROR)
                        .map(Object::toString).toList());
            }
            return new Translation(List.of(new Unit(result.className(), result.classFile())), null, List.of());
        }
    };

    /** 置き場から名前の写し句を引く。拡張子は大文字でも小文字でもよい。 */
    private static Optional<String> member(Path directory, String member, String extension) {
        for (String suffix : List.of(extension.toUpperCase(Locale.ROOT), extension.toLowerCase(Locale.ROOT), "")) {
            Path path = directory.resolve(member.toUpperCase(Locale.ROOT) + (suffix.isEmpty() ? "" : "." + suffix));
            if (Files.isRegularFile(path)) {
                return Optional.of(read(path));
            }
        }
        return Optional.empty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
