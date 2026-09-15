package dev.cobolonjava.ims.batch;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.dli.ImsRegion;
import dev.cobolonjava.ims.gen.ImsGenerationException;
import dev.cobolonjava.ims.psb.PcbDefinition;
import dev.cobolonjava.ims.psb.ProgramSpecification;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code DFSRRC00} — IMS のバッチ領域 (設計 78 §7.1、暫定判断 P-155)。
 *
 * <pre>
 * //LOAD     EXEC PGM=DFSRRC00,PARM='DLI,LOADCUST,IBLOAD'
 * //IMS      DD   DSN=IMS.PSBLIB,DISP=SHR        PSB と DBD の原文のライブラリ
 * //CUSTOMER DD   DSN=BANK.CUSTOMER,DISP=OLD     DBD の DATASET DD1= の名前
 * </pre>
 *
 * <p>{@code //IMS} のライブラリから PSB を読み、PSB が名指す DBD を同じライブラリから読む。実機のライブラリには
 * 生成した PSB / DBD (ACB) があるが、ここでは原文を置く。データベースは DBD の {@code DATASET DD1=} の DD
 * (書かれていなければ DBD 名の DD) から読み、プログラムが正常に戻ったときだけ書き戻す。異常終了したときは書かない。
 *
 * <p>プログラムの USING には、PSB の PCB を並びの順に渡す。{@code CMPAT=YES} の PSB だけ、先頭に I/O PCB を置く。
 */
public final class Dfsrrc00 implements CobolProgram {

    /** PSB と DBD の原文を置くライブラリの DD 名。 */
    static final String LIBRARY = "IMS";

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
        CodePage codePage = context.codePage();
        RegionParameters parameters = RegionParameters.of(codePage, arguments);
        if (!context.catalog().isAssigned(LIBRARY) || !Files.isDirectory(context.catalog().resolve(LIBRARY))) {
            throw new ImsBatchException("DD IMS must name the library that holds the PSB and DBD sources");
        }
        Path library = context.catalog().resolve(LIBRARY);
        ProgramSpecification psb = generated(parameters.psb(),
                () -> PsbParser.parse(member(library, parameters.psb(), codePage)));

        Map<String, DatabaseFile> files = new LinkedHashMap<>();
        Map<String, HierarchicalDatabase> databases = new LinkedHashMap<>();
        for (PcbDefinition pcb : psb.pcbs()) {
            if (!(pcb instanceof PcbDefinition.Database database) || databases.containsKey(database.dbdName())) {
                continue;
            }
            DatabaseDefinition dbd = generated(database.dbdName(),
                    () -> DbdParser.parse(member(library, database.dbdName(), codePage)));
            String ddName = dbd.dataSetName() != null ? dbd.dataSetName() : dbd.name();
            if (!context.catalog().isAssigned(ddName)) {
                throw new ImsBatchException("DD " + ddName + " for database " + dbd.name() + " is not allocated");
            }
            DatabaseFile file = new DatabaseFile(context.catalog().resolve(ddName), ddName, codePage);
            files.put(dbd.name(), file);
            databases.put(dbd.name(), file.read(dbd));
        }

        ImsRegion region;
        try {
            region = new ImsRegion(psb, databases.values(), codePage, psb.compatibility());
        } catch (IllegalArgumentException e) {
            throw new ImsBatchException(e.getMessage(), e);
        }
        ProgramContext ims = context.withProgramResolver(
                region.register(ProgramCatalog.builder()).legacyClassNameFallback().build());
        ProgramContext.Loaded loaded = ims.resolve(parameters.program(), loader);
        ProgramSignature signature = loaded.signature() != null
                ? loaded.signature() : loaded.program().programSignature();
        DataView[] pcbs = region.programArguments(signature);
        loaded.validateArguments(pcbs);
        loaded.program().runFresh(ims, pcbs);

        // 正常に戻ったときだけ書く。異常終了は例外としてここを通らない (P-155)
        for (Map.Entry<String, HierarchicalDatabase> database : databases.entrySet()) {
            files.get(database.getKey()).write(database.getValue());
        }
        context.setReturnCode(ims.returnCode());
    }

    private interface Generation<T> {
        T get();
    }

    private static <T> T generated(String member, Generation<T> generation) {
        try {
            return generation.get();
        } catch (ImsGenerationException e) {
            throw new ImsBatchException("member " + member + " of DD IMS: " + e.getMessage(), e);
        }
    }

    /**
     * ライブラリのメンバの原文。固定長 (80 桁の札) なら長さで、そうでなければ改行で行に切る。
     */
    private static String member(Path library, String name, CodePage codePage) {
        Path path = PartitionedDataSet.memberOf(library, name);
        if (!Files.isRegularFile(path)) {
            throw new ImsBatchException("member " + name + " is not in the library of DD IMS");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read member " + name + " of DD IMS", e);
        }
        DataSetAttributes attributes = DataSetAttributes.read(path);
        StringBuilder text = new StringBuilder();
        if (attributes.format() == RecordFormat.FIXED && attributes.recordLength() > 0) {
            for (int at = 0; at < bytes.length; at += attributes.recordLength()) {
                int end = Math.min(bytes.length, at + attributes.recordLength());
                text.append(codePage.decode(Arrays.copyOfRange(bytes, at, end))).append('\n');
            }
            return text.toString();
        }
        byte newline = codePage.encode("\n")[0];
        int start = 0;
        for (int at = 0; at <= bytes.length; at++) {
            if (at == bytes.length || bytes[at] == newline) {
                text.append(codePage.decode(Arrays.copyOfRange(bytes, start, at))).append('\n');
                start = at + 1;
            }
        }
        return text.toString();
    }
}
