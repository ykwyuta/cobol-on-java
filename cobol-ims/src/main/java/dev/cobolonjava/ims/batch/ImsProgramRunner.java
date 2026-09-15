package dev.cobolonjava.ims.batch;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.dli.ImsRegion;
import dev.cobolonjava.ims.dli.MessageQueue;
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
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * IMS の領域を組み立て、1 本のプログラムを動かす (設計 78 §4・§7.1、暫定判断 P-155、P-156)。
 *
 * <p>{@code //IMS} のライブラリから PSB と、PSB が名指す DBD の原文を読み、データベースを DBD の
 * {@code DATASET DD1=} の DD (無ければ DBD 名の DD) から読む。プログラムが正常に戻ったときだけ、I/O PCB に
 * 積んだ応答を送り、データベースを書き戻す。異常終了なら応答もデータベースも捨てる。
 *
 * <p>バッチ ({@code DFSRRC00} の DLI) はキューを持たず、PSB が {@code CMPAT=YES} のときだけ I/O PCB を置く。
 * 電文の処理 (MPP) はキューを持ち、I/O PCB を常に先頭に置く。
 */
public final class ImsProgramRunner {

    /** PSB と DBD の原文を置くライブラリの DD 名。 */
    public static final String LIBRARY = "IMS";

    private ImsProgramRunner() {
    }

    /**
     * @param queue 電文のキュー。バッチなら {@code null}
     * @return プログラムの復帰コード
     */
    public static int run(ProgramContext context, ClassLoader loader, String program, String psbName,
                          MessageQueue queue) {
        CodePage codePage = context.codePage();
        if (!context.catalog().isAssigned(LIBRARY) || !Files.isDirectory(context.catalog().resolve(LIBRARY))) {
            throw new ImsBatchException("DD IMS must name the library that holds the PSB and DBD sources");
        }
        Path library = context.catalog().resolve(LIBRARY);
        ProgramSpecification psb = generated(psbName, () -> PsbParser.parse(member(library, psbName, codePage)));

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
            region = new ImsRegion(psb, databases.values(), codePage, queue != null || psb.compatibility(), queue,
                    context.clock());
        } catch (IllegalArgumentException e) {
            throw new ImsBatchException(e.getMessage(), e);
        }
        ProgramContext ims = context.withProgramResolver(
                region.register(ProgramCatalog.builder()).legacyClassNameFallback().build());
        try {
            ProgramContext.Loaded loaded = ims.resolve(program, loader);
            ProgramSignature signature = loaded.signature() != null
                    ? loaded.signature() : loaded.program().programSignature();
            DataView[] pcbs = region.programArguments(signature);
            loaded.validateArguments(pcbs);
            loaded.program().runFresh(ims, pcbs);
        } catch (RuntimeException | Error e) {
            // 最後の同期点まで戻してから書く。確定した電文や CHKP までの更新は残る (P-157)
            region.finish(false);
            write(files, databases);
            throw e;
        }
        region.finish(true);
        write(files, databases);
        return ims.returnCode();
    }

    private static void write(Map<String, DatabaseFile> files, Map<String, HierarchicalDatabase> databases) {
        for (Map.Entry<String, HierarchicalDatabase> database : databases.entrySet()) {
            files.get(database.getKey()).write(database.getValue());
        }
    }

    private static <T> T generated(String member, Supplier<T> generation) {
        try {
            return generation.get();
        } catch (ImsGenerationException e) {
            throw new ImsBatchException("member " + member + " of DD IMS: " + e.getMessage(), e);
        }
    }

    /** ライブラリのメンバの原文。固定長 (80 桁の札) なら長さで、そうでなければ改行で行に切る。 */
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
