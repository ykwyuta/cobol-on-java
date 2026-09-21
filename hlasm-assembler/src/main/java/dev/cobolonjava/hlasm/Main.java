package dev.cobolonjava.hlasm;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code hlasm [-d dir] [-l] source...}。
 *
 * <p>{@code -l} は組み立て表 (変位と機械語) を出す。これは<b>実行とは別に</b>組み立てそのものを
 * 突き合わせるためにある (設計 27 §3)。
 *
 * <p>配備カタログはまだ書かない。カタログは引数の個数と最小の長さを持つが、HLASM の原文からは
 * どちらも決まらない。R1 の表を何個読むかを決めるのはプログラム自身だからである。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(out);
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        Path output = Path.of(".");
        boolean listing = false;
        List<Path> sources = new ArrayList<>();
        for (int k = 0; k < args.length; k++) {
            switch (args[k]) {
                case "-d" -> {
                    if (++k >= args.length) {
                        usage("-d requires a directory");
                        return;
                    }
                    output = Path.of(args[k]);
                }
                case "-l" -> listing = true;
                default -> sources.add(Path.of(args[k]));
            }
        }
        if (sources.isEmpty()) {
            usage("no source was given");
            return;
        }

        HlasmCompiler compiler = HlasmCompiler.standard();
        int failures = 0;
        for (Path source : sources) {
            HlasmCompiler.Result result = compiler.compile(source.getFileName().toString(),
                    Files.readString(source, StandardCharsets.UTF_8));
            result.diagnostics().forEach(System.err::println);
            if (!result.succeeded()) {
                failures++;
                continue;
            }
            if (listing) {
                printListing(out, result.module());
            }
            Path target = output.resolve(result.className().replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, result.classFile());
        }
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void printListing(PrintStream out, ObjectModule module) {
        out.printf("%s  %d byte(s)%n", module.name(), module.length());
        for (ObjectModule.Line line : module.listing()) {
            out.printf("%06X  %-16s  (line %d)%n", line.offset(), line.hex(), line.line());
        }
    }

    private static void usage(String message) {
        System.err.println(message);
        System.err.println("usage: hlasm [-d dir] [-l] source...");
        System.exit(2);
    }
}
