package dev.cobolonjava.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 生成済みプログラムの出力形式。キーは JVM のバイナリクラス名。 */
public enum ProgramOutput {
    CLASS, JAVA;

    public void write(Path root, String className, byte[] bytecode) throws IOException {
        write(root, Map.of(className, bytecode));
    }

    public void write(Path root, Map<String, byte[]> classes) throws IOException {
        if (this == JAVA) {
            for (var source : JavaDecompiler.decompile(classes).entrySet()) {
                JavaSource.write(root, source.getKey(), source.getValue());
            }
        } else {
            for (var program : classes.entrySet()) {
                Path target = root.resolve(program.getKey().replace('.', '/') + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, program.getValue());
            }
        }
    }
}
