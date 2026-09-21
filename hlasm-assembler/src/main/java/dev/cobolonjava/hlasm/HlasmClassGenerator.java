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
 * HLASM の原文を保持し、共通の呼出し ABI へ接続する小さな JVM クラスを生成する。
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

    static byte[] generate(String className, String fileName, String source, String programName) {
        String owner = className.replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, owner, null,
                "java/lang/Object", new String[] {Type.getInternalName(CobolProgram.class)});
        constructor(writer);
        initialStorage(writer);
        name(writer, programName);
        run(writer, fileName, source);
        metadata(writer, "programSignature", ProgramSignature.class, "signature", fileName, source);
        metadata(writer, "procedureManifest", ProcedureManifest.class, "procedureManifest",
                fileName, source);
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
    private static void initialStorage(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "initialStorage", "()[B", null, null);
        method.visitCode();
        method.visitInsn(ICONST_0);
        method.visitIntInsn(NEWARRAY, T_BYTE);
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

    private static void run(ClassWriter writer, String fileName, String source) {
        String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                Type.getType(Storage.class), Type.getType(ProgramContext.class),
                Type.getType(DataView[].class));
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "run", descriptor, null, null);
        method.visitCode();
        method.visitLdcInsn(fileName);
        method.visitLdcInsn(source);
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(ALOAD, 3);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HlasmRuntime.class), "execute",
                Type.getMethodDescriptor(Type.INT_TYPE, Type.getType(String.class),
                        Type.getType(String.class), Type.getType(ProgramContext.class),
                        Type.getType(DataView[].class)), false);
        // R15 の戻りコードは副プログラムとして呼ばれたときには捨てる。
        // 受け取るのは主プログラムとして動かしたとき (main) だけである
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void metadata(ClassWriter writer, String methodName, Class<?> returnType,
                                 String runtimeMethod, String fileName, String source) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, methodName,
                Type.getMethodDescriptor(Type.getType(returnType)), null, null);
        method.visitCode();
        method.visitLdcInsn(fileName);
        method.visitLdcInsn(source);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HlasmRuntime.class), runtimeMethod,
                Type.getMethodDescriptor(Type.getType(returnType), Type.getType(String.class),
                        Type.getType(String.class)), false);
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
