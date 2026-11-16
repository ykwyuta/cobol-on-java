package dev.cobolonjava.hlasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** HLASM の COPY とマクロのメンバを名前で引く。見つからなければ {@code null} を返す。 */
@FunctionalInterface
public interface SourceLibrary {

    String find(String member);

    /** 呼び出したマクロを収めるライブラリの名前。 */
    default String dataSetName(String member) {
        return member;
    }

    static SourceLibrary empty() {
        return member -> null;
    }

    /** UTF-8 のファイルを、拡張子なしまたは .asm / .hlasm / .inc / .mac のメンバとして読む。 */
    static SourceLibrary directory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return empty();
        }
        Map<String, Path> members = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String file = path.getFileName().toString();
                int dot = file.lastIndexOf('.');
                String suffix = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!suffix.isEmpty() && !suffix.equals("asm") && !suffix.equals("hlasm")
                        && !suffix.equals("inc") && !suffix.equals("mac")) {
                    continue;
                }
                String member = (dot < 0 ? file : file.substring(0, dot))
                        .toUpperCase(Locale.ROOT);
                if (!member.matches("[A-Z@$#][A-Z0-9@$#]{0,7}")) {
                    continue;
                }
                if (members.putIfAbsent(member, path) != null) {
                    throw new IOException("duplicate HLASM library member: " + member);
                }
            }
        }
        return new SourceLibrary() {
            @Override
            public String find(String member) {
                Path path = members.get(member.toUpperCase(Locale.ROOT));
                if (path == null) {
                    return null;
                }
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new IllegalStateException("cannot read HLASM member " + member, failure);
                }
            }

            @Override
            public String dataSetName(String member) {
                return directory.toString();
            }
        };
    }
}
