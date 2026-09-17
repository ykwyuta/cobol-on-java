package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** PL/I ソースを保持し、共通ランタイムへ接続する小さな JVM クラスを生成する。 */
final class PliClassGenerator implements Opcodes {

    private PliClassGenerator() {
    }

    static byte[] generate(String className, String fileName, String source) {
        String owner = className.replace('.', '/');
        String programInterface = Type.getInternalName(CobolProgram.class);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, owner, null,
                "java/lang/Object", new String[] {programInterface});
        constructor(writer);
        initialStorage(writer);
        run(writer, owner, fileName, source);
        signature(writer, fileName, source);
        procedureManifest(writer, fileName, source);
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

    private static void initialStorage(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "initialStorage", "()[B", null, null);
        method.visitCode();
        method.visitInsn(ICONST_0);
        method.visitIntInsn(NEWARRAY, T_BYTE);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void run(ClassWriter writer, String owner, String fileName, String source) {
        String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                Type.getType(Storage.class), Type.getType(ProgramContext.class),
                Type.getType(DataView[].class));
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "run", descriptor, null, null);
        method.visitCode();
        method.visitLdcInsn(fileName);
        method.visitLdcInsn(source);
        method.visitVarInsn(ALOAD, 2);
        method.visitVarInsn(ALOAD, 3);
        method.visitLdcInsn(Type.getObjectType(owner));
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(PliRuntime.class), "execute",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class),
                        Type.getType(String.class), Type.getType(ProgramContext.class),
                        Type.getType(DataView[].class), Type.getType(ClassLoader.class)), false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void signature(ClassWriter writer, String fileName, String source) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "programSignature",
                Type.getMethodDescriptor(Type.getType(ProgramSignature.class)), null, null);
        method.visitCode();
        method.visitLdcInsn(fileName);
        method.visitLdcInsn(source);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(PliRuntime.class), "signature",
                Type.getMethodDescriptor(Type.getType(ProgramSignature.class),
                        Type.getType(String.class), Type.getType(String.class)), false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void procedureManifest(ClassWriter writer, String fileName, String source) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "procedureManifest",
                Type.getMethodDescriptor(Type.getType(ProcedureManifest.class)), null, null);
        method.visitCode();
        method.visitLdcInsn(fileName);
        method.visitLdcInsn(source);
        method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(PliRuntime.class),
                "procedureManifest", Type.getMethodDescriptor(Type.getType(ProcedureManifest.class),
                        Type.getType(String.class), Type.getType(String.class)), false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void main(ClassWriter writer, String owner) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V",
                null, null);
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
