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
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.ims.store.DatabaseStores;
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
 * <p>{@code //IMS} のライブラリから PSB と、PSB が名指す DBD の原文を読み、データベースを置き場から読む。
 * 置き場は RDB が構成されていればそちら、無ければ DBD の {@code DATASET DD1=} の DD のデータセットである (P-160)。
 * 同期点ごとに置き場へ確定し、異常終了なら最後の同期点まで戻す (P-157)。
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
        return run(context, loader, program, psbName, queue, false);
    }

    /**
     * @param queue 電文のキュー。バッチなら {@code null}
     * @param ioPcb PSB の CMPAT によらず I/O PCB を先頭に置くか (BMP)。キューがあれば常に置く
     * @return プログラムの復帰コード
     */
    public static int run(ProgramContext context, ClassLoader loader, String program, String psbName,
                          MessageQueue queue, boolean ioPcb) {
        CodePage codePage = context.codePage();
        if (!context.catalog().isAssigned(LIBRARY) || !Files.isDirectory(context.catalog().resolve(LIBRARY))) {
            throw new ImsBatchException("DD IMS must name the library that holds the PSB and DBD sources");
        }
        Path library = context.catalog().resolve(LIBRARY);
        ProgramSpecification psb = generated(psbName, () -> PsbParser.parse(member(library, psbName, codePage)));

        // RDB の置き場が構成されていればそちら、無ければデータセットの置き場 (P-160)
        DatabaseStore configured = DatabaseStores.open(context);
        try (DatabaseStore store = configured != null ? configured : new DataSetDatabaseStore(context)) {
            Map<String, HierarchicalDatabase> databases = new LinkedHashMap<>();
            for (PcbDefinition pcb : psb.pcbs()) {
                if (!(pcb instanceof PcbDefinition.Database database) || databases.containsKey(database.dbdName())) {
                    continue;
                }
                DatabaseDefinition dbd = generated(database.dbdName(),
                        () -> DbdParser.parse(member(library, database.dbdName(), codePage)));
                databases.put(dbd.name(), store.open(dbd));
            }

            ImsRegion region;
            try {
                region = new ImsRegion(psb, databases.values(), codePage,
                        ioPcb || queue != null || psb.compatibility(), queue, context.clock())
                        .onCommit(store::commit)
                        .withInbox(store.inbox());
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
                // 最後の同期点まで戻す。置き場には同期点で確定した分だけが残る (P-157、P-160)
                region.finish(false);
                throw e;
            }
            // 正常終了も同期点であり、ここで置き場へ確定する
            region.finish(true);
            return ims.returnCode();
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
