package dev.cobolonjava.verify.ims;

import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.gen.ImsGenerationException;
import dev.cobolonjava.ims.gen.MacroReader;
import dev.cobolonjava.ims.gen.MacroStatement;
import dev.cobolonjava.ims.psb.PsbParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * IMS の DBDGEN / PSBGEN の原文を読めるか数える (設計 78 §2.1)。
 *
 * <p>DL/I の呼び出しを動かす前に、PCB と DBD を組み立てられるかを外の資産で測る。どちらの原文かは
 * 拡張子ではなく中身 ({@code DBD} 文か {@code PSBGEN} 文があるか) で決める。置き場の分け方は資産ごとに違う。
 */
public final class ImsGenerationRunner {

    /** 原文 1 本の結果。 */
    public record Outcome(Path file, Kind kind, String reason) {

        public boolean passed() {
            return reason == null;
        }
    }

    public enum Kind {
        DBD, PSB, UNKNOWN
    }

    private ImsGenerationRunner() {
    }

    /** 置き場の下の {@code .asm} をすべて読む。 */
    public static List<Outcome> run(Path root) {
        List<Outcome> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".asm"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                out.add(read(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static Outcome read(Path file) {
        String source;
        try {
            // 原文は印字できる 1 byte の文字だけで書かれている。土地の既定の文字集合に任せない
            source = Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Kind kind = Kind.UNKNOWN;
        try {
            kind = kindOf(MacroReader.read(source));
            switch (kind) {
                case DBD -> DbdParser.parse(source);
                case PSB -> PsbParser.parse(source);
                case UNKNOWN -> {
                    return new Outcome(file, kind, "neither a DBDGEN nor a PSBGEN source");
                }
            }
            return new Outcome(file, kind, null);
        } catch (ImsGenerationException e) {
            return new Outcome(file, kind, e.reason());
        }
    }

    private static Kind kindOf(List<MacroStatement> statements) {
        for (MacroStatement statement : statements) {
            if (statement.operation().equals("DBD")) {
                return Kind.DBD;
            }
            if (statement.operation().equals("PSBGEN")) {
                return Kind.PSB;
            }
        }
        return Kind.UNKNOWN;
    }

    /** 数と、通らなかった理由を多い順に書く。原文の中身は書かない (要件 NFR-042)。 */
    public static String text(List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("IMS の DBDGEN / PSBGEN (設計 78)\n");
        sb.append("============================\n\n");
        for (Kind kind : Kind.values()) {
            List<Outcome> of = outcomes.stream().filter(outcome -> outcome.kind() == kind).toList();
            if (of.isEmpty()) {
                continue;
            }
            long passed = of.stream().filter(Outcome::passed).count();
            sb.append(String.format("%-8s %3d 本: 読めた %d / 断った %d%n", kind, of.size(), passed,
                    of.size() - passed));
        }
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) {
            if (!outcome.passed()) {
                reasons.merge(outcome.reason(), 1, Integer::sum);
            }
        }
        if (!reasons.isEmpty()) {
            sb.append("\n通らなかった理由 (多い順)\n");
            reasons.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(entry -> sb.append(String.format("%6d  %s%n", entry.getValue(), entry.getKey())));
        }
        return sb.toString();
    }
}
