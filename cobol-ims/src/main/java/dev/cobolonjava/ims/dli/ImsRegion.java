package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.FieldDefinition;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import dev.cobolonjava.ims.psb.PcbDefinition;
import dev.cobolonjava.ims.psb.ProgramSpecification;
import dev.cobolonjava.ims.psb.SensitiveSegment;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 1 本のプログラムを動かす IMS の領域。PSB と、それが名指すデータベースを束ねる (設計 78 §2)。
 *
 * <p>PCB の記憶域を PSB の並びで作り、{@link #programArguments} でプログラムへ渡す。{@link #register} で
 * program catalog に {@code CBLTDLI} を置くと、COBOL の {@code CALL 'CBLTDLI'} がここへ来る。どの PCB への
 * 呼び出しかは、渡された PCB の記憶域そのもので見分ける。
 *
 * <p>PCB の記憶域はマスクの長さ (DB PCB は 36 byte とキー帰還域) の後ろに予備を持つ。プログラムは
 * KEYLEN より長いキー帰還域を書いたマスクを使うことが多く、実機ならその先も PCB の外の記憶域として読める。
 * 予備が無いと、マスクの 01 を PCB の番地に結んだ時点で記憶域の端を越えて止まる (暫定判断 P-154)。
 *
 * <p>I/O PCB と代替 PCB への呼び出し (IMS TM) はまだ持たない (設計 78 §4)。
 */
public final class ImsRegion {

    /** PCB のマスクの後ろに binary zero で置く予備。 */
    static final int RESERVE = 1024;
    /** 代替 PCB のマスクの長さ (宛先 8、予約 2、状態 2)。 */
    private static final int TERMINAL_MASK = 12;
    /** I/O PCB のマスクの長さ。端末名、状態、日時、入力の順序番号、MOD 名、利用者の欄を収める。 */
    private static final int IO_MASK = 64;

    private final ProgramSpecification psb;
    private final CodePage codePage;
    private final Map<String, HierarchicalDatabase> databases = new LinkedHashMap<>();
    private final List<Storage> storages = new ArrayList<>();
    private final Map<Storage, DatabasePcb> databasePcbs = new IdentityHashMap<>();
    private Storage ioStorage;
    private IoPcb ioPcb;

    /**
     * @throws IllegalArgumentException PSB が渡されていない DBD を名指すか、DBD と食い違うとき
     */
    public ImsRegion(ProgramSpecification psb, Collection<HierarchicalDatabase> databases, CodePage codePage) {
        this(psb, databases, codePage, false);
    }

    /**
     * @param ioPcb 先頭に I/O PCB を置くか。オンラインと BMP では常に、バッチでは PSB が {@code CMPAT=YES} のとき置く
     * @throws IllegalArgumentException PSB が渡されていない DBD を名指すか、DBD と食い違うとき
     */
    public ImsRegion(ProgramSpecification psb, Collection<HierarchicalDatabase> databases, CodePage codePage,
                     boolean ioPcb) {
        this(psb, databases, codePage, ioPcb, null, Clock.systemDefaultZone());
    }

    /**
     * 電文を処理する領域 (MPP)。I/O PCB への GU / GN / ISRT / PURG が {@code queue} へ行く (P-156)。
     *
     * @param queue 電文のキュー。バッチなら {@code null}
     * @param clock I/O PCB の日付と時刻の出どころ
     */
    public ImsRegion(ProgramSpecification psb, Collection<HierarchicalDatabase> databases, CodePage codePage,
                     boolean ioPcb, MessageQueue queue, Clock clock) {
        if (queue != null && !ioPcb) {
            throw new IllegalArgumentException("a message queue needs an I/O PCB");
        }
        this.psb = Objects.requireNonNull(psb, "psb");
        this.codePage = Objects.requireNonNull(codePage, "codePage");
        for (HierarchicalDatabase database : databases) {
            if (this.databases.putIfAbsent(database.definition().name(), database) != null) {
                throw new IllegalArgumentException("DBD " + database.definition().name() + " is given twice");
            }
        }
        if (ioPcb) {
            ioStorage = Storage.allocate(IO_MASK + RESERVE);
            storages.add(ioStorage);
            this.ioPcb = new IoPcb(ioStorage, queue, codePage, Objects.requireNonNull(clock, "clock"));
        }
        for (PcbDefinition pcb : psb.pcbs()) {
            if (pcb instanceof PcbDefinition.Database definition) {
                HierarchicalDatabase database = this.databases.get(definition.dbdName());
                if (database == null) {
                    throw new IllegalArgumentException("PSB " + psb.name() + " names DBD " + definition.dbdName()
                            + ", which was not given");
                }
                validate(definition, database.definition());
                Storage mask = Storage.allocate(DatabasePcb.KEY_FEEDBACK + definition.keyLength() + RESERVE);
                storages.add(mask);
                databasePcbs.put(mask, new DatabasePcb(definition, database, mask, codePage, this::delete));
            } else {
                storages.add(Storage.allocate(TERMINAL_MASK + RESERVE));
            }
        }
    }

    public ProgramSpecification psb() {
        return psb;
    }

    /** 名前でデータベースを引く。無ければ {@code null}。 */
    public HierarchicalDatabase database(String name) {
        return databases.get(name);
    }

    /** プログラムへ渡す PCB。PSB に書いた順に並び、それぞれ記憶域の全体を指す。 */
    public DataView[] programArguments() {
        return storages.stream().map(Storage::whole).toArray(DataView[]::new);
    }

    /**
     * プログラムの USING に合わせた PCB。
     *
     * <p>実機の IMS は PCB の番地を渡すだけで、プログラムがどの長さのマスクを書くかを知らない
     * ({@code 01 IOPCBA POINTER} と書くものも、56 byte のマスクを書くものもある)。生成クラスは引数の長さを
     * 宣言どおりに検査するので、同じ記憶域の先頭から宣言の長さで切って渡す。USING が PCB より少なければ、
     * 前から宣言の数だけ渡す。
     *
     * @param signature 生成クラスの署名。無ければ記憶域の全体を渡す
     * @throws DliCallException USING が PCB より多いか、マスクが PCB の記憶域 (予備を含む) を越えるとき
     */
    public DataView[] programArguments(ProgramSignature signature) {
        if (signature == null) {
            return programArguments();
        }
        List<ProgramParameter> parameters = signature.parameters();
        if (parameters.size() > storages.size()) {
            throw new DliCallException("program " + signature.programId().value() + " receives "
                    + parameters.size() + " parameters, but PSB " + psb.name() + " has " + storages.size() + " PCBs");
        }
        DataView[] out = new DataView[parameters.size()];
        for (int i = 0; i < out.length; i++) {
            Storage storage = storages.get(i);
            ProgramParameter parameter = parameters.get(i);
            if (parameter.minimumBytes() > storage.size()) {
                throw new DliCallException("the mask " + parameter.name() + " (" + parameter.minimumBytes()
                        + " bytes) is longer than PCB " + (i + 1) + " of PSB " + psb.name()
                        + " with its reserve (" + storage.size() + " bytes)");
            }
            out[i] = storage.view(0, Math.min(storage.size(), parameter.maximumBytes()));
        }
        return out;
    }

    /** {@code CBLTDLI} を catalog に置く。 */
    public ProgramCatalog.Builder register(ProgramCatalog.Builder builder) {
        return builder.javaProgram("CBLTDLI", () -> (context, arguments) -> call(arguments));
    }

    /**
     * {@code CALL 'CBLTDLI' USING [数,] 機能コード, PCB [, I/O 域 [, SSA...]]}。
     *
     * <p>先頭の数は省いてよい。4 byte で先頭の byte が 0 なら数とみなす。機能コードは印字できる文字で
     * 始まるので取り違えない。
     */
    void call(List<DataView> arguments) {
        int index = 0;
        if (!arguments.isEmpty() && arguments.get(0).length() == 4 && arguments.get(0).get(0) == 0) {
            byte[] count = arguments.get(0).toByteArray();
            int declared = ((count[0] & 0xFF) << 24) | ((count[1] & 0xFF) << 16)
                    | ((count[2] & 0xFF) << 8) | (count[3] & 0xFF);
            if (declared != arguments.size() - 1) {
                throw new DliCallException("the parameter count " + declared + " does not match the "
                        + (arguments.size() - 1) + " parameters that follow it");
            }
            index = 1;
        }
        if (arguments.size() < index + 2) {
            throw new DliCallException("CBLTDLI requires a function code and a PCB");
        }
        DataView functionView = arguments.get(index);
        if (functionView.length() < 4) {
            throw new DliCallException("a DL/I function code is 4 bytes");
        }
        String function = codePage.decode(functionView.subView(0, 4).toByteArray()).stripTrailing();
        DataView pcbView = arguments.get(index + 1);
        if (ioPcb != null && pcbView.offset() == 0 && pcbView.storage() == ioStorage) {
            ioPcb.call(function, arguments.size() > index + 2 ? arguments.get(index + 2) : null,
                    arguments.size() > index + 3 ? arguments.subList(index + 3, arguments.size()) : List.of());
            return;
        }
        DatabasePcb pcb = pcbView.offset() == 0 ? databasePcbs.get(pcbView.storage()) : null;
        if (pcb == null) {
            boolean terminal = pcbView.offset() == 0
                    && storages.stream().anyMatch(storage -> storage == pcbView.storage());
            throw new DliCallException(terminal
                    ? "DL/I calls on a TP PCB are not supported yet (design 78 section 4): " + function
                    : "the PCB parameter of CBLTDLI is not a PCB of PSB " + psb.name());
        }
        DataView io = arguments.size() > index + 2 ? arguments.get(index + 2) : null;
        List<DataView> ssas = arguments.size() > index + 3
                ? arguments.subList(index + 3, arguments.size()) : List.of();
        pcb.call(function, io, ssas);
    }

    /**
     * プログラムが戻ったあとに呼ぶ。正常に戻ったなら I/O PCB に積んだ応答を送り、異常終了なら捨てる (P-156)。
     */
    public void finish(boolean normal) {
        if (ioPcb != null) {
            ioPcb.finish(normal);
        }
    }

    /** 同じデータベースを見る PCB の位置を先に動かしてから消す。 */
    private void delete(HierarchicalDatabase database, Segment segment) {
        for (DatabasePcb pcb : databasePcbs.values()) {
            if (pcb.database() == database) {
                pcb.beforeDelete(segment);
            }
        }
        database.delete(segment);
    }

    /** SENSEG が DBD のセグメントと親に合い、KEYLEN が最も長い連結キーを収めるか。 */
    private void validate(PcbDefinition.Database pcb, DatabaseDefinition dbd) {
        int longest = 0;
        for (SensitiveSegment sensitive : pcb.segments()) {
            SegmentDefinition segment = dbd.segment(sensitive.name());
            if (segment == null) {
                throw new IllegalArgumentException("PSB " + psb.name() + ": SENSEG " + sensitive.name()
                        + " is not a segment of DBD " + dbd.name());
            }
            if (!Objects.equals(segment.parent(), sensitive.parent())) {
                throw new IllegalArgumentException("PSB " + psb.name() + ": the parent of SENSEG " + sensitive.name()
                        + " is " + segment.parent() + " in DBD " + dbd.name());
            }
            int key = 0;
            for (SegmentDefinition at = segment; at != null;
                 at = at.parent() == null ? null : dbd.segment(at.parent())) {
                FieldDefinition sequence = at.sequenceField();
                key += sequence == null ? 0 : sequence.bytes();
            }
            longest = Math.max(longest, key);
        }
        if (pcb.keyLength() < longest) {
            throw new IllegalArgumentException("PSB " + psb.name() + ": KEYLEN=" + pcb.keyLength()
                    + " of the PCB for DBD " + dbd.name() + " is shorter than the longest concatenated key ("
                    + longest + " bytes)");
        }
    }
}
