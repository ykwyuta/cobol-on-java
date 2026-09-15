package dev.cobolonjava.cics;

import dev.cobolonjava.cics.CicsFileDefinition.Organization;
import dev.cobolonjava.cics.CicsFilePort.Addressing;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.IndexedDataSet;
import dev.cobolonjava.runtime.file.KeyRelation;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.RelativeDataSet;
import dev.cobolonjava.runtime.file.SequentialDataSet;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 定義した file をバッチと同じデータセットとして持つ file control (暫定判断 P-131、P-136、P-147)。
 *
 * <h2>返す条件</h2>
 * <p>RESP / RESP2 は CICS TS の各命令の公開文書に書かれた値だけを返す。文書が値を示さない形
 * (鍵を変える REWRITE、EQUAL で始めた browse の位置づけ直しなど) は、近い条件を選ばず
 * {@link CicsTaskStateException} で失敗させる。
 *
 * <h2>回復と排他</h2>
 * <p>file は回復不能 (RECOVERY(NONE)) として扱う。SYNCPOINT ROLLBACK は書いた record を戻さない。
 * READ UPDATE で得た record は、REWRITE / DELETE / UNLOCK、SYNCPOINT、task の終わりで返す。
 * ほかの task が持つ record への READ UPDATE と DELETE は task の期限まで待つ。NOSUSPEND なら待たずに
 * RECORDBUSY を返す。文書は NOSUSPEND、CONSISTENT、REPEATABLE を RLS の file に限るが、RLS の区別を持たないので
 * どの file でも受ける (設計 85 §4.2、§5.3)。
 *
 * <h2>データセットの読み書き</h2>
 * <p>命令のたびにデータセットを開いて閉じる。バッチのジョブが同じデータセットを読めるように
 * するためであり、region の中の命令はすべて 1 つの監視で順に通す。RBA で引く命令はデータセットを
 * 頭から数えるので、大きな file では遅い。性能は測っていない。
 */
final class CicsFileControl implements CicsFilePort {

    private static final Result FILE_NOT_FOUND = new Result(CicsResponseCode.FILENOTFOUND, 1);
    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** IOERR (RESP2 120): file control の入出力の誤り。 */
    private static final Result IO_ERROR = new Result(CicsResponseCode.IOERR, 120);
    /** NOTFND (RESP2 80): 探した record が無い。 */
    private static final Result NOT_FOUND = new Result(CicsResponseCode.NOTFND, 80);
    /** ENDFILE (RESP2 90): browse が終わりを越えた。 */
    private static final Result END_FILE = new Result(CicsResponseCode.ENDFILE, 90);
    /** INVREQ (RESP2 47): TOKEN の値が使用中の token と合わない (REWRITE / DELETE / UNLOCK の頁)。 */
    private static final Result TOKEN_MISMATCH = new Result(CicsResponseCode.INVREQ, 47);
    /** RECORDBUSY (RESP2 107): NOSUSPEND で、record が他の task に持たれている (READ / DELETE の頁)。 */
    private static final Result RECORD_BUSY = new Result(CicsResponseCode.RECORDBUSY, 107);

    /**
     * データセットの record。id はデータセットの中の識別 (KSDS の鍵、RRDS と BDAM の相対レコード番号、
     * ESDS の 8 byte の RBA)、address は命令の RIDFLD の形の識別である。
     */
    private record Rec(byte[] id, byte[] data, byte[] address) {
    }

    /** 位置づけの識別 (RIDFLD の形) と関係。 */
    private record Search(byte[] address, KeyRelation relation) {
    }

    private record BrowseKey(CicsTaskId task, String file, int reqid) {
    }

    /** TOKEN で持つ record。 */
    private record Hold(CicsTaskId task, String file, byte[] id) {
    }

    private static final class Browse {
        private final Addressing addressing;
        private boolean generic;
        private boolean equal;
        /** 位置づけに使う識別の長さ。総称なら短い。 */
        private int keyLength;
        /** 位置づけの識別。null ならデータセットの終わり (RIDFLD がすべて X'FF')。 */
        private byte[] start;
        /** 直前に返した record の識別。位置づけのあとは null。 */
        private byte[] last;
        private boolean previous;
        /** RIDFLD に置いた値。次の読みで違っていれば、利用者が位置を変えたとみなす。 */
        private byte[] echo;
        /** RESETBR が record を見つけられなかった。 */
        private boolean lost;

        private Browse(Addressing addressing) {
            this.addressing = addressing;
        }
    }

    /** 入出力の誤り。命令の境界で IOERR に変える。 */
    private static final class IoFailure extends RuntimeException {
        private IoFailure(String message) {
            super(message, null, false, false);
        }
    }

    private final Map<String, CicsFileDefinition> definitions;
    /** 更新のために持つ record。file ごとに、id の 16 進 → 持つ task。 */
    private final Map<String, Map<String, CicsTaskId>> lockedBy = new HashMap<>();
    /** task ごとに、file → TOKEN を使わずに更新のために持つ record の id。 */
    private final Map<CicsTaskId, Map<String, byte[]>> updates = new HashMap<>();
    /** TOKEN で持つ record。token は region の中で一意にする。 */
    private final Map<Integer, Hold> tokens = new HashMap<>();
    private final Map<BrowseKey, Browse> browses = new HashMap<>();
    private int nextToken = 1;

    CicsFileControl(List<CicsFileDefinition> definitions) {
        this.definitions = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(CicsFileDefinition::name, Function.identity()));
    }

    @Override
    public OptionalInt keyLengthOf(String file) {
        CicsFileDefinition definition = definitions.get(file);
        return definition == null || definition.organization() != Organization.KSDS
                ? OptionalInt.empty() : OptionalInt.of(definition.keyLength());
    }

    @Override
    public boolean variableLength(String file) {
        CicsFileDefinition definition = definitions.get(file);
        return definition != null && definition.variable();
    }

    // ---- 1 件ずつの命令 ----

    @Override
    public synchronized Found write(CicsTaskId task, String file, byte[] ridfld, Addressing addressing,
                                    byte[] record, boolean massInsert) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return found(FILE_NOT_FOUND);
        }
        if (!definition.allows(CicsFileDefinition.Service.ADD)) {
            throw state("WRITE FILE(" + file + ") is not allowed by the file definition;"
                    + " the INVREQ RESP2 value is not verified");
        }
        Addressing form = normalized(definition, addressing);
        if (massInsert && definition.organization() == Organization.BDAM) {
            // INVREQ (RESP2 38): BDAM の file への MASSINSERT
            return found(new Result(CicsResponseCode.INVREQ, 38));
        }
        Result length = lengthCondition(definition, record);
        if (length != null) {
            return found(length);
        }
        byte[] id;
        switch (definition.organization()) {
            case RRDS -> {
                if (form != Addressing.RRN || ridfld.length != Integer.BYTES || ByteBuffer.wrap(ridfld).getInt() < 1) {
                    throw state("WRITE FILE(" + file + ") requires RRN with a relative record number of 1 or greater");
                }
                id = ridfld.clone();
            }
            case KSDS -> {
                if (form != Addressing.KEY) {
                    throw state("WRITE FILE(" + file + ") " + form + " applies only to a relative record file"
                            + " or an entry-sequenced file");
                }
                requireKeyInRecord(definition, record, "WRITE");
                if (ridfld.length != definition.keyLength() || !Arrays.equals(ridfld, keyOf(definition, record))) {
                    // RIDFLD とレコードの鍵が食い違う形は INVREQ だが、RESP2 の値を確かめていない
                    throw state("WRITE FILE(" + file + ") RIDFLD does not match the record key");
                }
                id = ridfld.clone();
            }
            case ESDS -> {
                requireAddressing(definition, form, "WRITE");
                // ESDS の WRITE は常に終わりに足す。RIDFLD は書いた record の RBA を返す域である
                id = null;
            }
            default -> {
                requireAddressing(definition, form, "WRITE");
                requireLength(ridfld, Integer.BYTES, "WRITE", definition);
                int block = ByteBuffer.wrap(ridfld, 0, Integer.BYTES).getInt();
                if (block < 0 || block == Integer.MAX_VALUE) {
                    throw state("WRITE FILE(" + file + ") relative block number " + block + " is not verified");
                }
                id = int4(block + 1);
            }
        }
        return io(() -> {
            Store store = open(definition, true);
            try {
                byte[] target = id;
                byte[] address = ridfld;
                if (store instanceof EsdsStore esds) {
                    long rba = esds.end();
                    target = long8(rba);
                    address = rbaAddress(form, rba);
                }
                String written = store.insert(target, record);
                return switch (written) {
                    case FileStatus.OK -> new Found(CicsResponseCode.NORMAL, 0, null, address);
                    // DUPREC (RESP2 150): 同じ鍵のレコードが既にある
                    case FileStatus.DUPLICATE_KEY -> found(new Result(CicsResponseCode.DUPREC, 150));
                    // NOSPACE (RESP2 100): 装置に置く場所が無い
                    case FileStatus.BOUNDARY -> found(new Result(CicsResponseCode.NOSPACE, 100));
                    default -> throw state("WRITE FILE(" + file + ") failed with file status " + written);
                };
            } finally {
                store.close();
            }
        }, failure -> found(IO_ERROR));
    }

    @Override
    public synchronized Found read(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, int keyLength,
                                   boolean generic, boolean gteq, boolean update, Access access, Duration maxWait) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return found(FILE_NOT_FOUND);
        }
        if (update && !definition.allows(CicsFileDefinition.Service.UPDATE)) {
            // INVREQ (RESP2 20): 定義が更新を許さない file への READ UPDATE
            return found(new Result(CicsResponseCode.INVREQ, 20));
        }
        if (!update && !definition.allows(CicsFileDefinition.Service.READ)) {
            throw state("READ FILE(" + file + ") is not allowed by the file definition;"
                    + " the INVREQ RESP2 value is not verified");
        }
        boolean token = update && access.token();
        if (update && !token && holding(task, file) != null) {
            // INVREQ (RESP2 28): REWRITE / DELETE / UNLOCK を挟まずに、TOKEN の無い 2 度目の READ UPDATE
            return found(new Result(CicsResponseCode.INVREQ, 28));
        }
        Addressing form = normalized(definition, addressing);
        Result invalid = keyLengthCondition(definition, keyLength, generic);
        if (invalid != null) {
            return found(invalid);
        }
        Search search = search(definition, ridfld, form, keyLength, generic, gteq, "READ");
        long deadline = deadline(maxWait);
        while (true) {
            Rec record;
            try {
                record = locate(definition, form, search.address(), search.relation());
            } catch (IoFailure failure) {
                return found(IO_ERROR);
            }
            if (record == null) {
                return found(NOT_FOUND);
            }
            CicsTaskId owner = ownerOf(file, record.id());
            // CONSISTENT / REPEATABLE の読みは、他の task が更新のために持つ record を待つ (設計 85 §5.3)
            boolean busy = owner != null && (update || (access.consistent() && !owner.equals(task)));
            if (!busy) {
                if (!update) {
                    return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.address());
                }
                int issued = 0;
                if (token) {
                    issued = holdToken(task, file, record.id());
                } else {
                    hold(task, file, record.id());
                }
                return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.address(), issued);
            }
            if (access.noSuspend()) {
                return found(RECORD_BUSY);
            }
            await(deadline, (update ? "READ UPDATE" : "READ") + " FILE(" + file + ")");
        }
    }

    @Override
    public synchronized Result rewrite(CicsTaskId task, String file, byte[] record, int token) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return FILE_NOT_FOUND;
        }
        byte[] held;
        if (token != ABSENT) {
            Hold hold = heldBy(task, file, token);
            if (hold == null) {
                return TOKEN_MISMATCH;
            }
            held = hold.id();
        } else {
            held = holding(task, file);
            if (held == null) {
                // INVREQ (RESP2 30): TOKEN の無い REWRITE の前に、TOKEN の無い READ UPDATE が無い
                return new Result(CicsResponseCode.INVREQ, 30);
            }
        }
        Result length = lengthCondition(definition, record);
        if (length != null) {
            return length;
        }
        if (definition.organization() == Organization.KSDS) {
            requireKeyInRecord(definition, record, "REWRITE");
            if (!Arrays.equals(keyOf(definition, record), held)) {
                // 文書は鍵を変えてはならないと書くが、変えたときの条件を示さない
                throw state("REWRITE FILE(" + file + ") must not change the record key");
            }
        }
        return io(() -> {
            Store store = open(definition, true);
            String status;
            try {
                if (definition.organization() == Organization.BDAM && definition.variable()) {
                    Rec current = store.seek(held, KeyRelation.EQUAL);
                    if (current != null && current.data().length != record.length) {
                        // INVREQ (RESP2 46): BDAM の可変長の record の長さを変える
                        return new Result(CicsResponseCode.INVREQ, 46);
                    }
                }
                status = store.replace(held, record);
            } finally {
                store.close();
            }
            return switch (status) {
                case FileStatus.OK -> {
                    releaseHeld(task, file, token);
                    yield NORMAL;
                }
                case FileStatus.NO_RECORD -> {
                    releaseHeld(task, file, token);
                    // NOTFND (RESP2 80): 読んだあとで record が消えていた
                    yield NOT_FOUND;
                }
                // ESDS の record の長さは変えられない。文書に条件が無いので、他の区分に入らない VSAM の
                // 誤りとして ILLOGIC (RESP2 110) にする (設計 85 §5.1、推定)
                case FileStatus.REWRITE_LENGTH -> new Result(CicsResponseCode.ILLOGIC, 110);
                default -> throw state("REWRITE FILE(" + file + ") failed with file status " + status);
            };
        }, failure -> IO_ERROR);
    }

    @Override
    public synchronized Deleted delete(CicsTaskId task, String file, byte[] ridfld, Addressing addressing,
                                       int keyLength, boolean generic, int token, boolean noSuspend,
                                       Duration maxWait) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return deleted(FILE_NOT_FOUND);
        }
        if (!definition.allows(CicsFileDefinition.Service.DELETE)) {
            // INVREQ (RESP2 20): 定義が削除を許さない
            return deleted(new Result(CicsResponseCode.INVREQ, 20));
        }
        if (definition.organization() == Organization.ESDS) {
            // INVREQ (RESP2 21): ESDS の record は消せない
            return deleted(new Result(CicsResponseCode.INVREQ, 21));
        }
        if (definition.organization() == Organization.BDAM) {
            // INVREQ (RESP2 27): BDAM のデータセットの record は消せない
            return deleted(new Result(CicsResponseCode.INVREQ, 27));
        }
        if (ridfld == null) {
            byte[] held;
            if (token != ABSENT) {
                Hold hold = heldBy(task, file, token);
                if (hold == null) {
                    return deleted(TOKEN_MISMATCH);
                }
                held = hold.id();
            } else {
                held = holding(task, file);
                if (held == null) {
                    // INVREQ (RESP2 31): RIDFLD の無い DELETE の前に READ UPDATE が無い
                    return deleted(new Result(CicsResponseCode.INVREQ, 31));
                }
            }
            return io(() -> {
                Store store = open(definition, true);
                String status;
                try {
                    status = store.remove(held);
                } finally {
                    store.close();
                }
                releaseHeld(task, file, token);
                return switch (status) {
                    case FileStatus.OK -> new Deleted(CicsResponseCode.NORMAL, 0, 1);
                    case FileStatus.NO_RECORD -> deleted(NOT_FOUND);
                    default -> throw state("DELETE FILE(" + file + ") failed with file status " + status);
                };
            }, failure -> deleted(IO_ERROR));
        }
        if (holding(task, file) != null) {
            throw state("DELETE FILE(" + file + ") with RIDFLD while the task holds a READ UPDATE"
                    + " on the same file is not verified");
        }
        if (generic && definition.organization() != Organization.KSDS) {
            // INVREQ (RESP2 22): KSDS 以外への総称の DELETE
            return deleted(new Result(CicsResponseCode.INVREQ, 22));
        }
        Result invalid = keyLengthCondition(definition, keyLength, generic);
        if (invalid != null) {
            return deleted(invalid);
        }
        if (generic && keyLength == 0) {
            throw state("DELETE FILE(" + file + ") GENERIC KEYLENGTH(0) is not verified");
        }
        Addressing form = normalized(definition, addressing);
        Search search = search(definition, ridfld, form, keyLength, generic, false, "DELETE");
        long deadline = deadline(maxWait);
        while (true) {
            List<byte[]> targets;
            try {
                targets = targets(definition, form, search, generic);
            } catch (IoFailure failure) {
                return deleted(IO_ERROR);
            }
            if (targets.isEmpty()) {
                return deleted(NOT_FOUND);
            }
            List<byte[]> free = targets.stream().filter(id -> ownerOf(file, id) == null).toList();
            if (free.size() < targets.size() && !noSuspend) {
                await(deadline, "DELETE FILE(" + file + ")");
                continue;
            }
            // NOSUSPEND では、持たれていない record だけを消して RECORDBUSY を返す。NUMREC は消した数 (DELETE の頁)
            boolean busy = free.size() < targets.size();
            return io(() -> {
                Store store = open(definition, true);
                int count = 0;
                try {
                    for (byte[] id : free) {
                        String status = store.remove(id);
                        if (FileStatus.OK.equals(status)) {
                            count++;
                        } else if (!FileStatus.NO_RECORD.equals(status)) {
                            throw state("DELETE FILE(" + file + ") failed with file status " + status);
                        }
                    }
                } finally {
                    store.close();
                }
                if (busy) {
                    return new Deleted(RECORD_BUSY.response(), RECORD_BUSY.response2(), count);
                }
                return count == 0 ? deleted(NOT_FOUND) : new Deleted(CicsResponseCode.NORMAL, 0, count);
            }, failure -> deleted(IO_ERROR));
        }
    }

    @Override
    public synchronized Result unlock(CicsTaskId task, String file, int token) {
        if (!definitions.containsKey(Objects.requireNonNull(file, "file"))) {
            return FILE_NOT_FOUND;
        }
        if (token != ABSENT) {
            if (heldBy(task, file, token) == null) {
                return TOKEN_MISMATCH;
            }
            releaseToken(token);
            return NORMAL;
        }
        release(task, file);
        return NORMAL;
    }

    // ---- browse ----

    @Override
    public synchronized Result startBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld,
                                           Addressing addressing, int keyLength, boolean generic, boolean equal) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return FILE_NOT_FOUND;
        }
        if (!definition.allows(CicsFileDefinition.Service.BROWSE)) {
            // INVREQ (RESP2 20): 定義が browse を許さない
            return new Result(CicsResponseCode.INVREQ, 20);
        }
        BrowseKey key = new BrowseKey(task, file, reqid);
        if (browses.containsKey(key)) {
            // INVREQ (RESP2 33): 同じ REQID の browse が既にある
            return new Result(CicsResponseCode.INVREQ, 33);
        }
        Browse browse = new Browse(normalized(definition, addressing));
        Result positioned = position(definition, browse, ridfld, keyLength, generic, equal, "STARTBR");
        if (positioned.response() == CicsResponseCode.NORMAL) {
            browses.put(key, browse);
        }
        return positioned;
    }

    @Override
    public synchronized Result resetBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld,
                                           Addressing addressing, int keyLength, boolean generic, boolean equal) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return FILE_NOT_FOUND;
        }
        Browse browse = browses.get(new BrowseKey(task, file, reqid));
        if (browse == null) {
            // INVREQ (RESP2 36): REQID か file 名が STARTBR と合わない
            return new Result(CicsResponseCode.INVREQ, 36);
        }
        if (browse.addressing != normalized(definition, addressing)) {
            // INVREQ (RESP2 37): record の識別の形を STARTBR から変えた
            return new Result(CicsResponseCode.INVREQ, 37);
        }
        Result positioned = position(definition, browse, ridfld, keyLength, generic, equal, "RESETBR");
        if (positioned.response() == CicsResponseCode.NOTFND) {
            browse.lost = true;
        }
        return positioned;
    }

    @Override
    public synchronized Found readNext(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing,
                                       int keyLength, boolean update, Access access, Duration maxWait) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return found(FILE_NOT_FOUND);
        }
        Browse browse = browses.get(new BrowseKey(task, file, reqid));
        if (browse == null) {
            // INVREQ (RESP2 34): REQID か file 名が STARTBR と合わない
            return found(new Result(CicsResponseCode.INVREQ, 34));
        }
        if (browse.addressing != normalized(definition, addressing)) {
            // INVREQ (RESP2 37): record の識別の形を STARTBR から変えた
            return found(new Result(CicsResponseCode.INVREQ, 37));
        }
        if (update && !definition.allows(CicsFileDefinition.Service.UPDATE)) {
            // INVREQ (RESP2 20): 定義が更新を許さない
            return found(new Result(CicsResponseCode.INVREQ, 20));
        }
        requirePosition(browse, "READNEXT", file);
        Result invalid = reposition(definition, browse, ridfld, keyLength, "READNEXT");
        if (invalid != null) {
            return found(invalid);
        }
        long deadline = deadline(maxWait);
        while (true) {
            Rec record;
            try {
                if (browse.last == null) {
                    record = browse.start == null ? null
                            : locate(definition, browse.addressing, browse.start,
                                    browse.equal ? KeyRelation.EQUAL : KeyRelation.NOT_LESS);
                } else if (browse.previous) {
                    // 向きを変えると、同じ record をもう一度読む
                    record = locate(definition, browse.addressing, browse.last, KeyRelation.NOT_LESS);
                } else {
                    record = locate(definition, browse.addressing, browse.last, KeyRelation.GREATER);
                }
            } catch (IoFailure failure) {
                return found(IO_ERROR);
            }
            if (record == null) {
                return found(END_FILE);
            }
            if (update && ownerOf(file, record.id()) != null) {
                if (access.noSuspend()) {
                    return found(RECORD_BUSY);
                }
                await(deadline, "READNEXT UPDATE FILE(" + file + ")");
                continue;
            }
            browse.last = record.address();
            browse.previous = false;
            browse.echo = record.address().clone();
            int issued = update ? holdToken(task, file, record.id()) : 0;
            return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.address(), issued);
        }
    }

    @Override
    public synchronized Found readPrevious(CicsTaskId task, String file, int reqid, byte[] ridfld,
                                           Addressing addressing, int keyLength, boolean update, Access access,
                                           Duration maxWait) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return found(FILE_NOT_FOUND);
        }
        Browse browse = browses.get(new BrowseKey(task, file, reqid));
        if (browse == null) {
            // INVREQ (RESP2 41): REQID か file 名が STARTBR と合わない
            return found(new Result(CicsResponseCode.INVREQ, 41));
        }
        if (browse.addressing != normalized(definition, addressing)) {
            // INVREQ (RESP2 37): record の識別の形を STARTBR から変えた
            return found(new Result(CicsResponseCode.INVREQ, 37));
        }
        if (browse.generic) {
            // INVREQ (RESP2 24): GENERIC で始めた browse に READPREV
            return found(new Result(CicsResponseCode.INVREQ, 24));
        }
        if (update && !definition.allows(CicsFileDefinition.Service.UPDATE)) {
            // INVREQ (RESP2 20): 定義が更新を許さない
            return found(new Result(CicsResponseCode.INVREQ, 20));
        }
        requirePosition(browse, "READPREV", file);
        Result invalid = reposition(definition, browse, ridfld, keyLength, "READPREV");
        if (invalid != null) {
            return found(invalid);
        }
        long deadline = deadline(maxWait);
        while (true) {
            Rec record;
            try {
                if (browse.last == null) {
                    if (browse.start == null) {
                        record = locate(definition, browse.addressing, highest(definition, browse.addressing),
                                KeyRelation.NOT_GREATER);
                    } else {
                        record = locate(definition, browse.addressing, browse.start, KeyRelation.EQUAL);
                        if (record == null) {
                            // NOTFND (RESP2 80): 直前の STARTBR / RESETBR の RIDFLD に当たる record が無い
                            return found(NOT_FOUND);
                        }
                    }
                } else if (!browse.previous) {
                    // 向きを変えると、同じ record をもう一度読む
                    record = locate(definition, browse.addressing, browse.last, KeyRelation.NOT_GREATER);
                } else {
                    record = locate(definition, browse.addressing, browse.last, KeyRelation.LESS);
                }
            } catch (IoFailure failure) {
                return found(IO_ERROR);
            }
            if (record == null) {
                return found(END_FILE);
            }
            if (update && ownerOf(file, record.id()) != null) {
                if (access.noSuspend()) {
                    return found(RECORD_BUSY);
                }
                await(deadline, "READPREV UPDATE FILE(" + file + ")");
                continue;
            }
            browse.last = record.address();
            browse.previous = true;
            browse.echo = record.address().clone();
            int issued = update ? holdToken(task, file, record.id()) : 0;
            return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.address(), issued);
        }
    }

    @Override
    public synchronized Result endBrowse(CicsTaskId task, String file, int reqid) {
        if (!definitions.containsKey(Objects.requireNonNull(file, "file"))) {
            return FILE_NOT_FOUND;
        }
        if (browses.remove(new BrowseKey(task, file, reqid)) == null) {
            // INVREQ (RESP2 35): REQID か file 名が成功した STARTBR と合わない
            return new Result(CicsResponseCode.INVREQ, 35);
        }
        return NORMAL;
    }

    @Override
    public synchronized void releaseUnitOfWork(CicsTaskId task) {
        releaseAll(task);
    }

    @Override
    public synchronized void releaseTask(CicsTaskId task) {
        releaseAll(task);
    }

    // ---- 位置づけ ----

    private Result position(CicsFileDefinition definition, Browse browse, byte[] ridfld, int keyLength,
                            boolean generic, boolean equal, String command) {
        Result invalid = keyLengthCondition(definition, keyLength, generic);
        if (invalid != null) {
            return invalid;
        }
        if (!generic && endOfDataSet(definition, ridfld, browse.addressing)) {
            // すべて X'FF' の RIDFLD は、READPREV のためにデータセットの終わりへ位置づける
            browse.generic = false;
            browse.equal = equal;
            browse.keyLength = identifierLength(definition, browse.addressing);
            browse.start = null;
            browse.last = null;
            browse.previous = false;
            browse.echo = Arrays.copyOf(ridfld, browse.keyLength);
            browse.lost = false;
            return NORMAL;
        }
        if (generic && keyLength == 0 && equal) {
            throw state(command + " FILE(" + definition.name() + ") GENERIC KEYLENGTH(0) requires GTEQ");
        }
        Search search = search(definition, ridfld, browse.addressing, keyLength, generic, !equal, command);
        Rec found;
        try {
            found = locate(definition, browse.addressing, search.address(), search.relation());
        } catch (IoFailure failure) {
            return IO_ERROR;
        }
        if (found == null) {
            return NOT_FOUND;
        }
        browse.generic = generic;
        browse.equal = equal;
        browse.keyLength = search.address().length;
        browse.start = search.address();
        browse.last = null;
        browse.previous = false;
        browse.echo = search.address().clone();
        browse.lost = false;
        return NORMAL;
    }

    /**
     * READNEXT / READPREV の KEYLENGTH と RIDFLD が位置を変えるか。
     *
     * <p>文書が書くのは、GTEQ で始めた browse では変えた RIDFLD 以上の最初の record へ、GENERIC で始めた
     * browse では総称の鍵で位置づけ直すこと、KEYLENGTH(0) は先頭へ戻すことである。EQUAL で始めた
     * browse の位置づけ直しは書かれていないので失敗させる。
     */
    private Result reposition(CicsFileDefinition definition, Browse browse, byte[] ridfld, int keyLength,
                              String command) {
        if (browse.addressing != Addressing.KEY) {
            if (keyLength != ABSENT) {
                throw state(command + " KEYLENGTH applies only to a key-sequenced file");
            }
        } else if (browse.generic) {
            if (keyLength != ABSENT) {
                if (keyLength < 0) {
                    // INVREQ (RESP2 42): GENERIC の KEYLENGTH が負
                    return new Result(CicsResponseCode.INVREQ, 42);
                }
                if (keyLength >= definition.keyLength()) {
                    // INVREQ (RESP2 25): GENERIC の KEYLENGTH が鍵の長さ以上
                    return new Result(CicsResponseCode.INVREQ, 25);
                }
                if (keyLength == 0) {
                    // KEYLENGTH(0) は browse を先頭へ戻す
                    restart(browse, new byte[0]);
                    return null;
                }
                if (keyLength != browse.keyLength) {
                    requireLength(ridfld, keyLength, command, definition);
                    browse.keyLength = keyLength;
                    restart(browse, Arrays.copyOf(ridfld, keyLength));
                    return null;
                }
            }
        } else if (keyLength != ABSENT && keyLength != definition.keyLength()) {
            // INVREQ (RESP2 26): KEYLENGTH が定義の鍵の長さと違う
            return new Result(CicsResponseCode.INVREQ, 26);
        }
        requireLength(ridfld, browse.echo.length, command, definition);
        if (Arrays.equals(Arrays.copyOf(ridfld, browse.echo.length), browse.echo)) {
            return null;
        }
        if (browse.equal) {
            throw state(command + " FILE(" + definition.name() + ") changed RIDFLD in a browse started with EQUAL;"
                    + " the repositioning is not verified");
        }
        int length = browse.generic ? browse.keyLength : identifierLength(definition, browse.addressing);
        requireLength(ridfld, length, command, definition);
        byte[] start = Arrays.copyOf(ridfld, length);
        if (!browse.generic && allHigh(start)) {
            browse.start = null;
            browse.last = null;
            browse.previous = false;
            browse.echo = start;
            return null;
        }
        if (numbered(browse.addressing) && ByteBuffer.wrap(start).getInt() < 0) {
            throw state(command + " relative record or block number must not be negative");
        }
        restart(browse, start);
        return null;
    }

    private static void restart(Browse browse, byte[] start) {
        browse.equal = false;
        browse.start = start;
        browse.last = null;
        browse.previous = false;
        browse.echo = start.clone();
    }

    private static void requirePosition(Browse browse, String command, String file) {
        if (browse.lost) {
            throw state(command + " FILE(" + file + ") after a RESETBR that found no record is not verified");
        }
    }

    private static boolean endOfDataSet(CicsFileDefinition definition, byte[] ridfld, Addressing addressing) {
        int length = identifierLength(definition, addressing);
        return accepts(definition, addressing) && ridfld.length >= length
                && allHigh(Arrays.copyOf(ridfld, length));
    }

    private static boolean allHigh(byte[] bytes) {
        for (byte value : bytes) {
            if (value != (byte) 0xFF) {
                return false;
            }
        }
        return bytes.length > 0;
    }

    /** 番号を先頭の 4 byte の符号つき 2 進で持つ形。 */
    private static boolean numbered(Addressing addressing) {
        return addressing == Addressing.RRN || addressing == Addressing.BLOCK || addressing == Addressing.DEBKEY
                || addressing == Addressing.DEBREC;
    }

    private static int identifierLength(CicsFileDefinition definition, Addressing addressing) {
        return switch (addressing) {
            case KEY -> definition.keyLength();
            case RRN, RBA, BLOCK -> Integer.BYTES;
            case XRBA, DEBREC -> Long.BYTES;
            case DEBKEY -> Integer.BYTES + definition.keyLength();
        };
    }

    /** READPREV でデータセットの終わりから読むときの識別。 */
    private static byte[] highest(CicsFileDefinition definition, Addressing addressing) {
        return switch (addressing) {
            case KEY -> {
                byte[] key = new byte[definition.keyLength()];
                Arrays.fill(key, (byte) 0xFF);
                yield key;
            }
            case RRN -> int4(Integer.MAX_VALUE);
            // RBA は符号なしで比べるので、すべて X'FF' が最大である
            case RBA -> int4(-1);
            case XRBA -> long8(Long.MAX_VALUE);
            // 相対 block 番号に 1 を足して相対レコード番号にするので、あふれない最大
            case BLOCK -> int4(Integer.MAX_VALUE - 1);
            case DEBREC -> ByteBuffer.allocate(Long.BYTES).putInt(Integer.MAX_VALUE - 1).putInt(0).array();
            case DEBKEY -> {
                byte[] address = new byte[Integer.BYTES + definition.keyLength()];
                ByteBuffer.wrap(address).putInt(Integer.MAX_VALUE - 1);
                Arrays.fill(address, Integer.BYTES, address.length, (byte) 0xFF);
                yield address;
            }
        };
    }

    /** KEYLENGTH と GENERIC の組み合わせの条件。文書に RESP2 のあるものだけ。 */
    private static Result keyLengthCondition(CicsFileDefinition definition, int keyLength, boolean generic) {
        if (definition.organization() != Organization.KSDS) {
            return null;
        }
        if (generic) {
            if (keyLength == ABSENT) {
                throw state("GENERIC requires KEYLENGTH: FILE(" + definition.name() + ")");
            }
            if (keyLength < 0) {
                // INVREQ (RESP2 42): GENERIC の KEYLENGTH が負
                return new Result(CicsResponseCode.INVREQ, 42);
            }
            if (keyLength >= definition.keyLength()) {
                // INVREQ (RESP2 25): GENERIC の KEYLENGTH が鍵の長さ以上
                return new Result(CicsResponseCode.INVREQ, 25);
            }
        } else if (keyLength != ABSENT && keyLength != definition.keyLength()) {
            // INVREQ (RESP2 26): GENERIC の無い KEYLENGTH が定義の鍵の長さと違う
            return new Result(CicsResponseCode.INVREQ, 26);
        }
        return null;
    }

    /** BDAM の file では、RBA などを書かない RIDFLD を block の参照と読む。 */
    private static Addressing normalized(CicsFileDefinition definition, Addressing addressing) {
        Objects.requireNonNull(addressing, "addressing");
        return definition.organization() == Organization.BDAM && addressing == Addressing.KEY
                ? Addressing.BLOCK : addressing;
    }

    /** 編成が識別の形を受けるか。KSDS と RRDS の RBA は、鍵・番号の順に数えた RBA とする (設計 85 §5.1)。 */
    private static boolean accepts(CicsFileDefinition definition, Addressing addressing) {
        return switch (definition.organization()) {
            case KSDS -> addressing == Addressing.KEY || addressing == Addressing.RBA || addressing == Addressing.XRBA;
            case RRDS -> addressing == Addressing.RRN || addressing == Addressing.RBA || addressing == Addressing.XRBA;
            case ESDS -> addressing == Addressing.RBA || addressing == Addressing.XRBA;
            case BDAM -> addressing == Addressing.BLOCK || addressing == Addressing.DEBKEY
                    || addressing == Addressing.DEBREC;
        };
    }

    private static void requireAddressing(CicsFileDefinition definition, Addressing addressing, String command) {
        if (accepts(definition, addressing)) {
            return;
        }
        String label = command + " FILE(" + definition.name() + ") ";
        throw state(label + switch (definition.organization()) {
            case KSDS -> addressing == Addressing.RRN ? "RRN applies only to a relative record file"
                    : addressing + " applies only to a BDAM file";
            case RRDS -> "is a relative record file and requires RRN";
            case ESDS -> "is an entry-sequenced file and requires RBA or XRBA";
            case BDAM -> "is a BDAM file and takes a block reference, DEBKEY, or DEBREC";
        });
    }

    /** RIDFLD から位置づけの識別を作る。keyLengthCondition を通したあとに呼ぶ。 */
    private static Search search(CicsFileDefinition definition, byte[] ridfld, Addressing addressing, int keyLength,
                                 boolean generic, boolean gteq, String command) {
        KeyRelation relation = gteq ? KeyRelation.NOT_LESS : KeyRelation.EQUAL;
        requireAddressing(definition, addressing, command);
        if (addressing == Addressing.KEY) {
            if (generic && keyLength == 0 && !gteq) {
                throw state(command + " FILE(" + definition.name() + ") GENERIC KEYLENGTH(0) requires GTEQ");
            }
            int length = generic ? keyLength : definition.keyLength();
            requireLength(ridfld, length, command, definition);
            return new Search(Arrays.copyOf(ridfld, length), relation);
        }
        if (generic || keyLength != ABSENT) {
            throw state(command + " GENERIC and KEYLENGTH apply only to a key-sequenced file");
        }
        int length = identifierLength(definition, addressing);
        requireLength(ridfld, length, command, definition);
        byte[] address = Arrays.copyOf(ridfld, length);
        if (numbered(addressing)) {
            int number = ByteBuffer.wrap(address).getInt();
            boolean invalid = addressing == Addressing.RRN ? number < 0 || (number == 0 && !gteq) : number < 0;
            if (invalid || number == Integer.MAX_VALUE && addressing != Addressing.RRN) {
                throw state(command + " FILE(" + definition.name() + ") relative "
                        + (addressing == Addressing.RRN ? "record" : "block") + " number " + number
                        + " is not verified");
            }
        }
        return new Search(address, relation);
    }

    private static void requireLength(byte[] ridfld, int length, String command, CicsFileDefinition definition) {
        if (ridfld.length < length) {
            throw state(command + " FILE(" + definition.name() + ") RIDFLD of " + ridfld.length
                    + " bytes is shorter than the record identifier of " + length + " bytes");
        }
    }

    /** 書く record の長さの条件。 */
    private static Result lengthCondition(CicsFileDefinition definition, byte[] record) {
        if (record.length < 1) {
            throw state("record length must be at least 1 byte: FILE(" + definition.name() + ")");
        }
        if (!definition.variable() && record.length != definition.recordLength()) {
            // LENGERR (RESP2 14): 固定長の file へ定義と違う長さを書く
            return new Result(CicsResponseCode.LENGERR, 14);
        }
        if (definition.variable() && record.length > definition.recordLength()) {
            // LENGERR (RESP2 12): 定義の最大の長さを越える
            return new Result(CicsResponseCode.LENGERR, 12);
        }
        return null;
    }

    private static void requireKeyInRecord(CicsFileDefinition definition, byte[] record, String command) {
        if (record.length < definition.keyOffset() + definition.keyLength()) {
            throw state(command + " FILE(" + definition.name() + ") record does not contain the whole key");
        }
    }

    private static byte[] keyOf(CicsFileDefinition definition, byte[] record) {
        return Arrays.copyOfRange(record, definition.keyOffset(), definition.keyOffset() + definition.keyLength());
    }

    // ---- 排他 ----

    private byte[] holding(CicsTaskId task, String file) {
        Map<String, byte[]> held = updates.get(task);
        return held == null ? null : held.get(file);
    }

    private CicsTaskId ownerOf(String file, byte[] id) {
        Map<String, CicsTaskId> locks = lockedBy.get(file);
        return locks == null ? null : locks.get(HexFormat.of().formatHex(id));
    }

    private void hold(CicsTaskId task, String file, byte[] id) {
        lockedBy.computeIfAbsent(file, ignored -> new HashMap<>()).put(HexFormat.of().formatHex(id), task);
        updates.computeIfAbsent(task, ignored -> new HashMap<>()).put(file, id.clone());
    }

    /** TOKEN で record を持ち、token を返す。token は 1 から数え、ABSENT と 0 は使わない。 */
    private int holdToken(CicsTaskId task, String file, byte[] id) {
        lockedBy.computeIfAbsent(file, ignored -> new HashMap<>()).put(HexFormat.of().formatHex(id), task);
        int issued = nextToken;
        nextToken = nextToken == Integer.MAX_VALUE ? 1 : nextToken + 1;
        tokens.put(issued, new Hold(task, file, id.clone()));
        return issued;
    }

    private Hold heldBy(CicsTaskId task, String file, int token) {
        Hold hold = tokens.get(token);
        return hold != null && hold.task().equals(task) && hold.file().equals(file) ? hold : null;
    }

    private void releaseHeld(CicsTaskId task, String file, int token) {
        if (token != ABSENT) {
            releaseToken(token);
        } else {
            release(task, file);
        }
    }

    private void releaseToken(int token) {
        Hold hold = tokens.remove(token);
        if (hold == null) {
            return;
        }
        unlockRecord(hold.file(), hold.id());
    }

    private void release(CicsTaskId task, String file) {
        Map<String, byte[]> held = updates.get(task);
        byte[] id = held == null ? null : held.remove(file);
        if (id == null) {
            return;
        }
        if (held.isEmpty()) {
            updates.remove(task);
        }
        unlockRecord(file, id);
    }

    private void unlockRecord(String file, byte[] id) {
        Map<String, CicsTaskId> locks = lockedBy.get(file);
        if (locks != null) {
            locks.remove(HexFormat.of().formatHex(id));
        }
        notifyAll();
    }

    private void releaseAll(CicsTaskId task) {
        Map<String, byte[]> held = updates.get(task);
        if (held != null) {
            for (String file : new ArrayList<>(held.keySet())) {
                release(task, file);
            }
        }
        List<Integer> owned = tokens.entrySet().stream()
                .filter(entry -> entry.getValue().task().equals(task)).map(Map.Entry::getKey).toList();
        owned.forEach(this::releaseToken);
        browses.keySet().removeIf(key -> key.task().equals(task));
    }

    private static long deadline(Duration maxWait) {
        return maxWait == null ? Long.MAX_VALUE : System.nanoTime() + Math.max(0, maxWait.toNanos());
    }

    private void await(long deadline, String command) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw state(command + " wait for a record held by another task exceeded the task deadline");
        }
        try {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw state(command + " wait was interrupted");
        }
    }

    // ---- データセット ----

    /** 識別の形と関係で record を探す。 */
    private Rec locate(CicsFileDefinition definition, Addressing addressing, byte[] address, KeyRelation relation) {
        Store store = open(definition, false);
        try {
            return switch (addressing) {
                case KEY, RRN -> store.seek(address, relation);
                case RBA, XRBA -> byRba(store.all(), addressing, rbaOf(address), relation);
                case BLOCK, DEBKEY, DEBREC -> byBlock(definition, store, addressing, address, relation);
            };
        } finally {
            store.close();
        }
    }

    /** 並びの順に長さを足して RBA を数え、関係を満たす record を探す。 */
    private static Rec byRba(List<Rec> records, Addressing addressing, long target, KeyRelation relation) {
        long rba = 0;
        Rec found = null;
        for (Rec record : records) {
            if (relation.holds(Long.compare(rba, target))) {
                Rec hit = new Rec(record.id(), record.data(), rbaAddress(addressing, rba));
                if (relation.searchesForward()) {
                    return hit;
                }
                // 小さいほうを探す関係では、条件を満たす最後の record
                found = hit;
            }
            rba += record.data().length;
        }
        return found;
    }

    /**
     * BDAM の block の参照で探す (設計 85 §5.4)。相対 block 番号に 1 を足した相対レコード番号の record が block である。
     * EQUAL の探し方では、DEBREC の相対 record 番号は 0 だけが、DEBKEY の鍵は record の鍵と同じときだけが見つかる。
     */
    private static Rec byBlock(CicsFileDefinition definition, Store store, Addressing addressing, byte[] address,
                               KeyRelation relation) {
        int block = ByteBuffer.wrap(address, 0, Integer.BYTES).getInt();
        Rec record = store.seek(int4(block + 1), relation);
        if (record == null) {
            return null;
        }
        int number = ByteBuffer.wrap(record.id()).getInt() - 1;
        boolean exact = relation == KeyRelation.EQUAL;
        byte[] located;
        switch (addressing) {
            case DEBREC -> {
                if (exact && ByteBuffer.wrap(address, Integer.BYTES, Integer.BYTES).getInt() != 0) {
                    return null;
                }
                located = ByteBuffer.allocate(Long.BYTES).putInt(number).putInt(0).array();
            }
            case DEBKEY -> {
                requireKeyInRecord(definition, record.data(), "DEBKEY");
                byte[] key = keyOf(definition, record.data());
                if (exact && !Arrays.equals(Arrays.copyOfRange(address, Integer.BYTES, Integer.BYTES + key.length),
                        key)) {
                    return null;
                }
                located = ByteBuffer.allocate(Integer.BYTES + key.length).putInt(number).put(key).array();
            }
            default -> located = int4(number);
        }
        return new Rec(record.id(), record.data(), located);
    }

    private List<byte[]> targets(CicsFileDefinition definition, Addressing addressing, Search search,
                                 boolean generic) {
        if (!generic) {
            Rec record = locate(definition, addressing, search.address(), KeyRelation.EQUAL);
            return record == null ? List.of() : List.of(record.id());
        }
        Store store = open(definition, false);
        try {
            return ((KeyedStore) store).idsWithPrefix(search.address());
        } finally {
            store.close();
        }
    }

    private static Store open(CicsFileDefinition definition, boolean write) {
        return switch (definition.organization()) {
            case KSDS -> new KeyedStore(definition, write);
            case RRDS, BDAM -> new RelativeStore(definition, write);
            case ESDS -> new EsdsStore(definition);
        };
    }

    private static byte[] int4(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] long8(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    /** RBA を命令の形にする。RBA は 4 byte の符号なし、XRBA は 8 byte。 */
    private static byte[] rbaAddress(Addressing addressing, long rba) {
        if (addressing == Addressing.XRBA) {
            return long8(rba);
        }
        if (rba > 0xFFFF_FFFFL) {
            throw state("RBA " + rba + " does not fit a fullword; the file requires XRBA");
        }
        return int4((int) rba);
    }

    private static long rbaOf(byte[] address) {
        return address.length == Long.BYTES ? ByteBuffer.wrap(address).getLong()
                : Integer.toUnsignedLong(ByteBuffer.wrap(address).getInt());
    }

    private static <T> T io(Supplier<T> action, Function<IoFailure, T> failure) {
        try {
            return action.get();
        } catch (IoFailure caught) {
            return failure.apply(caught);
        }
    }

    private static Found found(Result result) {
        return new Found(result.response(), result.response2(), null, null);
    }

    private static Deleted deleted(Result result) {
        return new Deleted(result.response(), result.response2(), 0);
    }

    private static CicsTaskStateException state(String message) {
        return new CicsTaskStateException(message);
    }

    private static DataSetAttributes attributes(CicsFileDefinition definition) {
        return new DataSetAttributes(definition.variable() ? RecordFormat.VARIABLE : RecordFormat.FIXED,
                definition.recordLength(), definition.codePage());
    }

    private static void opened(String status, CicsFileDefinition definition) {
        if (!FileStatus.succeeded(status)) {
            throw state("FILE(" + definition.name() + ") cannot open the data set: " + status);
        }
    }

    /** 状態コードを確かめる。入出力の誤りは IOERR に、ほかの想定しない値は失敗にする。 */
    private static void checked(String status, String operation, CicsFileDefinition definition) {
        if (FileStatus.IO_ERROR.equals(status)) {
            throw new IoFailure(operation + " FILE(" + definition.name() + ")");
        }
        if (!FileStatus.OK.equals(status)) {
            throw state(operation + " FILE(" + definition.name() + ") failed with file status " + status);
        }
    }

    private interface Store {
        /** 関係を満たす最初の record。無ければ null。 */
        Rec seek(byte[] id, KeyRelation relation);

        /** すべての record を、データセットの並び (鍵、番号、入力) の順に。 */
        List<Rec> all();

        String insert(byte[] id, byte[] record);

        String replace(byte[] id, byte[] record);

        String remove(byte[] id);

        void close();
    }

    private static final class KeyedStore implements Store {
        private final CicsFileDefinition definition;
        private final IndexedDataSet dataSet;

        private KeyedStore(CicsFileDefinition definition, boolean write) {
            this.definition = definition;
            dataSet = IndexedDataSet.at(definition.path(), attributes(definition),
                    new IndexedDataSet.Key(definition.keyOffset(), definition.keyLength(), false), List.of());
            opened(dataSet.open(write ? OpenMode.IO : OpenMode.INPUT, true), definition);
        }

        @Override
        public Rec seek(byte[] id, KeyRelation relation) {
            String started = dataSet.start(0, id, relation);
            if (FileStatus.NO_RECORD.equals(started)) {
                return null;
            }
            checked(started, "START", definition);
            return next();
        }

        private Rec next() {
            byte[] buffer = new byte[definition.recordLength()];
            String read = dataSet.read(buffer);
            if (FileStatus.AT_END.equals(read)) {
                return null;
            }
            checked(read, "READ", definition);
            byte[] data = Arrays.copyOf(buffer, dataSet.lastLength());
            byte[] key = keyOf(definition, data);
            return new Rec(key, data, key);
        }

        @Override
        public List<Rec> all() {
            // 開いた直後の順次読みは、鍵の順に先頭から読む
            List<Rec> out = new ArrayList<>();
            for (Rec record = next(); record != null; record = next()) {
                out.add(record);
            }
            return out;
        }

        /** 総称の鍵で始まる record の id をすべて。 */
        private List<byte[]> idsWithPrefix(byte[] prefix) {
            List<byte[]> out = new ArrayList<>();
            String started = dataSet.start(0, prefix, KeyRelation.EQUAL);
            if (FileStatus.NO_RECORD.equals(started)) {
                return out;
            }
            checked(started, "START", definition);
            for (Rec record = next(); record != null; record = next()) {
                if (!Arrays.equals(Arrays.copyOf(record.id(), prefix.length), prefix)) {
                    break;
                }
                out.add(record.id());
            }
            return out;
        }

        @Override
        public String insert(byte[] id, byte[] record) {
            return dataSet.writeKey(record);
        }

        @Override
        public String replace(byte[] id, byte[] record) {
            return dataSet.rewriteKey(record);
        }

        @Override
        public String remove(byte[] id) {
            return dataSet.deleteKey(id);
        }

        @Override
        public void close() {
            String closed = dataSet.close();
            if (!FileStatus.succeeded(closed)) {
                throw state("FILE(" + definition.name() + ") cannot close the data set: " + closed);
            }
        }
    }

    private static final class RelativeStore implements Store {
        private final CicsFileDefinition definition;
        private final RelativeDataSet dataSet;

        private RelativeStore(CicsFileDefinition definition, boolean write) {
            this.definition = definition;
            dataSet = RelativeDataSet.at(definition.path(), attributes(definition));
            opened(dataSet.open(write ? OpenMode.IO : OpenMode.INPUT, true), definition);
        }

        @Override
        public Rec seek(byte[] id, KeyRelation relation) {
            String started = dataSet.start(ByteBuffer.wrap(id).getInt(), relation);
            if (FileStatus.NO_RECORD.equals(started)) {
                return null;
            }
            checked(started, "START", definition);
            return next();
        }

        private Rec next() {
            byte[] buffer = new byte[definition.recordLength()];
            String read = dataSet.read(buffer);
            if (FileStatus.AT_END.equals(read)) {
                return null;
            }
            checked(read, "READ", definition);
            byte[] number = int4(dataSet.currentNumber());
            return new Rec(number, Arrays.copyOf(buffer, dataSet.lastLength()), number);
        }

        @Override
        public List<Rec> all() {
            List<Rec> out = new ArrayList<>();
            for (Rec record = next(); record != null; record = next()) {
                out.add(record);
            }
            return out;
        }

        @Override
        public String insert(byte[] id, byte[] record) {
            return dataSet.writeAt(ByteBuffer.wrap(id).getInt(), record);
        }

        @Override
        public String replace(byte[] id, byte[] record) {
            return dataSet.rewriteAt(ByteBuffer.wrap(id).getInt(), record);
        }

        @Override
        public String remove(byte[] id) {
            return dataSet.deleteAt(ByteBuffer.wrap(id).getInt());
        }

        @Override
        public void close() {
            String closed = dataSet.close();
            if (!FileStatus.succeeded(closed)) {
                throw state("FILE(" + definition.name() + ") cannot close the data set: " + closed);
            }
        }
    }

    /**
     * ESDS を順編成のデータセットに置く (設計 85 §5.1)。id は 8 byte の RBA。読むときは頭から読んで RBA を数え、
     * 書き足しは EXTEND、書き換えは I-O で開き直す。
     */
    private static final class EsdsStore implements Store {
        private final CicsFileDefinition definition;
        private List<byte[]> records;

        private EsdsStore(CicsFileDefinition definition) {
            this.definition = definition;
        }

        private SequentialDataSet dataSet() {
            return SequentialDataSet.at(definition.path(), attributes(definition));
        }

        private List<byte[]> records() {
            if (records == null) {
                SequentialDataSet dataSet = dataSet();
                opened(dataSet.open(OpenMode.INPUT, true), definition);
                try {
                    List<byte[]> out = new ArrayList<>();
                    byte[] buffer = new byte[definition.recordLength()];
                    while (true) {
                        String read = dataSet.read(buffer);
                        if (FileStatus.AT_END.equals(read)) {
                            break;
                        }
                        checked(read, "READ", definition);
                        out.add(Arrays.copyOf(buffer, dataSet.lastLength()));
                    }
                    records = out;
                } finally {
                    closeSet(dataSet);
                }
            }
            return records;
        }

        private void closeSet(SequentialDataSet dataSet) {
            String closed = dataSet.close();
            if (!FileStatus.succeeded(closed)) {
                throw state("FILE(" + definition.name() + ") cannot close the data set: " + closed);
            }
        }

        /** 次に書く record の RBA。 */
        private long end() {
            return records().stream().mapToLong(record -> record.length).sum();
        }

        @Override
        public Rec seek(byte[] id, KeyRelation relation) {
            Rec found = byRba(all(), Addressing.XRBA, ByteBuffer.wrap(id).getLong(), relation);
            return found == null ? null : new Rec(found.address(), found.data(), found.address());
        }

        @Override
        public List<Rec> all() {
            List<Rec> out = new ArrayList<>();
            long rba = 0;
            for (byte[] record : records()) {
                byte[] id = long8(rba);
                out.add(new Rec(id, record, id));
                rba += record.length;
            }
            return out;
        }

        @Override
        public String insert(byte[] id, byte[] record) {
            SequentialDataSet dataSet = dataSet();
            opened(dataSet.open(OpenMode.EXTEND, true), definition);
            try {
                return dataSet.write(record);
            } finally {
                closeSet(dataSet);
                records = null;
            }
        }

        @Override
        public String replace(byte[] id, byte[] record) {
            long target = ByteBuffer.wrap(id).getLong();
            SequentialDataSet dataSet = dataSet();
            String status = dataSet.open(OpenMode.IO, false);
            if (!FileStatus.succeeded(status)) {
                return FileStatus.NO_RECORD;
            }
            try {
                byte[] buffer = new byte[definition.recordLength()];
                long rba = 0;
                while (true) {
                    String read = dataSet.read(buffer);
                    if (FileStatus.AT_END.equals(read)) {
                        return FileStatus.NO_RECORD;
                    }
                    checked(read, "READ", definition);
                    if (rba == target) {
                        return dataSet.rewrite(record);
                    }
                    if (rba > target) {
                        return FileStatus.NO_RECORD;
                    }
                    rba += dataSet.lastLength();
                }
            } finally {
                closeSet(dataSet);
                records = null;
            }
        }

        @Override
        public String remove(byte[] id) {
            throw state("FILE(" + definition.name() + ") an entry-sequenced record cannot be deleted");
        }

        @Override
        public void close() {
        }
    }
}
