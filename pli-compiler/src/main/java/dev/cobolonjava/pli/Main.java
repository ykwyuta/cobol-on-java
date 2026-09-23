package dev.cobolonjava.pli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code plic [-d dir] [-I include-dir]... source...}。
 *
 * <p>{@code -I} は何度でも書ける。書いた順に探す。翻訳の手順そのものは {@link PliBuild} にあり、
 * Maven プラグインも同じものを呼ぶ。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: plic [-d dir] [-I include-dir]... source...");
            System.exit(2);
            return;
        }
        PliBuild.Result result = PliBuild.run(
                new PliBuild.Request(options.sources, options.output, options.includes),
                System.err::println);
        if (!result.succeeded()) System.exit(1);
    }

    private record Options(List<Path> sources, Path output, List<Path> includes) {
        static Options parse(String[] args) {
            List<Path> sources = new ArrayList<>();
            Path output = Path.of(".");
            List<Path> includes = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-d" -> output = Path.of(next(args, ++i, "-d"));
                    case "-I" -> includes.add(Path.of(next(args, ++i, "-I")));
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException("unknown option: " + args[i]);
                        }
                        sources.add(Path.of(args[i]));
                    }
                }
            }
            if (sources.isEmpty()) throw new IllegalArgumentException("no source file given");
            return new Options(List.copyOf(sources), output, List.copyOf(includes));
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
            return args[index];
        }
    }
}
