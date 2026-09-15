package dev.cobolonjava.ims.batch;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code DFSRRC00} — IMS のバッチ領域 (設計 78 §7.1、暫定判断 P-155)。
 *
 * <pre>
 * //LOAD     EXEC PGM=DFSRRC00,PARM='DLI,LOADCUST,IBLOAD'     BMP も受ける (電文を読む IN= を除く)
 * //IMS      DD   DSN=IMS.PSBLIB,DISP=SHR        PSB と DBD の原文のライブラリ
 * //CUSTOMER DD   DSN=BANK.CUSTOMER,DISP=OLD     DBD の DATASET DD1= の名前
 * </pre>
 *
 * <p>領域の組み立てとプログラムの実行は {@link ImsProgramRunner} が受け持つ。ここは PARM を読むだけである。
 */
public final class Dfsrrc00 implements CobolProgram {

    private final ClassLoader loader;

    public Dfsrrc00(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        RegionParameters parameters = RegionParameters.of(context.codePage(), arguments);
        context.setReturnCode(ImsProgramRunner.run(context, loader, parameters.program(), parameters.psb(), null,
                parameters.ioPcb(), parameters.restartId()));
    }
}
