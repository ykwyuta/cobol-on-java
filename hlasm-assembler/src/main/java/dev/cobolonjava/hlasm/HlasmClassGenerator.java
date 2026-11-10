package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * HLASM の組立て済み機械語を保持し、共通の呼出し ABI へ接続する JVM クラスを生成する。
 *
 * <p>調査レポートで「薄い殻はそのまま使える」と書いたところである。{@code PliClassGenerator} と
 * 同じ形で、意味論は一切持たない。持っているのは {@code HlasmRuntime} である。
 *
 * <p>HLASM ではこの形が<b>PL/I のとき以上に必然</b>になる。{@code EX} による自己書き換えと、
 * レジスタの値へ飛ぶ計算分岐があるため、各命令を JVM のラベルへ展開する方式では
 * 分岐の行き先を静的に閉じられない。
 */
final class HlasmClassGenerator implements Opcodes {

    private HlasmClassGenerator() {
    }

    static byte[] generate(String className, ObjectModule module) {
        String owner = className.replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, owner, null,
                "java/lang/Object", new String[] {Type.getInternalName(CobolProgram.class)});
        constructor(writer);
        initialStorage(writer, module);
        name(writer, module.name());
        run(writer, module, owner);
        metadata(writer, "programSignature", ProgramSignature.class, "signatureForModule",
                module.name());
        metadata(writer, "procedureManifest", ProcedureManifest.class,
                "procedureManifestForModule", module.name());
        main(writer, owner);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /** HLASM は作業場所を自分の制御節に持つので、呼ぶ側の記憶域は要らない。 */
    /** 機械語を Base64 の文字列として積む。長い文字列は定数プールに入らないので切って継ぐ。 */
    private static void pushEncodedText(MethodVisitor method, ObjectModule module) {
        String encoded = java.util.Base64.getEncoder().encodeToString(module.text());
        method.visitTypeInsn(NEW, "java/lang/StringBuilder");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        for (int offset = 0; offset < encoded.length(); offset += 16_000) {
            method.visitLdcInsn(encoded.substring(offset, Math.min(encoded.length(), offset + 16_000)));
            method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        }
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
    }

    /**
     * プログラムの記憶域は<b>機械語そのもの</b>である。
     *
     * <p>HLASM のモジュールは命令と作業域を同じ節に持つ。ランタイムが副プログラムの記憶域を
     * 呼び出しをまたいで持ち続けるので、ここに置けばモジュールの中の値も残る。
     */
    private static void initialStorage(ClassWriter writer, ObjectModule module) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "initialStorage", "()[B", null, null);
        method.visitCode();
        pushEncodedText(method, module);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HlasmRuntime.class), "decode",
                "(Ljava/lang/String;)[B", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void name(ClassWriter writer, String programName) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "name", "()Ljava/lang/String;",
                null, null);
        method.visitCode();
        method.visitLdcInsn(programName);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void run(ClassWriter writer, ObjectModule module, String owner) {
        String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                Type.getType(Storage.class), Type.getType(ProgramContext.class),
                Type.getType(DataView[].class));
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "run", descriptor, null, null);
        method.visitCode();
        method.visitLdcInsn(module.name());
        method.visitVarInsn(ALOAD, 1);
        pushEncodedText(method, module);
        method.visitLdcInsn(module.entryOffset());
        StringBuilder relocations = new StringBuilder();
        for (ObjectModule.Relocation field : module.relocations()) {
            relocations.append(field.offset()).append(':').append(field.length()).append(';');
        }
        StringBuilder externals = new StringBuilder();
        for (ObjectModule.External field : module.externals()) {
            externals.append(field.offset()).append(':').append(field.length()).append(':')
                    .append(field.name()).append(';');
        }
        method.visitLdcInsn(relocations.toString());
        method.visitLdcInsn(externals.toString());
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(ALOAD, 3);
        method.visitLdcInsn(Type.getObjectType(owner));
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HlasmRuntime.class),
                "executeLoaded",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class),
                        Type.getType(Storage.class), Type.getType(String.class), Type.INT_TYPE,
                        Type.getType(String.class), Type.getType(String.class),
                        Type.getType(ProgramContext.class), Type.getType(DataView[].class),
                        Type.getType(ClassLoader.class)), false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void metadata(ClassWriter writer, String methodName, Class<?> returnType,
                                 String runtimeMethod, String programName) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, methodName,
                Type.getMethodDescriptor(Type.getType(returnType)), null, null);
        method.visitCode();
        method.visitLdcInsn(programName);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HlasmRuntime.class), runtimeMethod,
                Type.getMethodDescriptor(Type.getType(returnType), Type.getType(String.class)), false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void main(ClassWriter writer, String owner) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        method.visitCode();
        method.visitTypeInsn(NEW, owner);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, owner, "<init>", "()V", false);
        method.visitMethodInsn(INVOKEVIRTUAL, owner, "runMain", "()I", false);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
