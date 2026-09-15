package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.IndexedDataSet;
import dev.cobolonjava.runtime.file.KeyRelation;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.RelativeDataSet;
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
 * 定義した file をバッチと同じデータセットとして持つ file control (暫定判断 P-131、P-136)。
 *
 * <h2>返す条件</h2>
 * <p>RESP / RESP2 は CICS TS の各命令の公開文書に書かれた値だけを返す。文書が値を示さない形
 * (鍵を変える REWRITE、EQUAL で始めた browse の位置づけ直しなど) は、近い条件を選ばず
 * {@link CicsTaskStateException} で失敗させる。
 *
 * <h2>回復と排他</h2>
 * <p>file は回復不能 (RECOVERY(NONE)) として扱う。SYNCPOINT ROLLBACK は書いた record を戻さない。
 * READ UPDATE で得た record は、REWRITE / DELETE / UNLOCK、SYNCPOINT、task の終わりで返す。
 * ほかの task が持つ record への READ UPDATE と DELETE は task の期限まで待つ。
 *
 * <h2>データセットの読み書き</h2>
 * <p>命令のたびにデータセットを開いて閉じる。バッチのジョブが同じデータセットを読めるように
 * するためであり、region の中の命令はすべて 1 つの監視で順に通す。大きな file の性能は測っていない。
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

    private record Rec(byte[] id, byte[] data) {
    }

    /** 位置づけの鍵と関係。 */
    private record Search(byte[] id, KeyRelation relation) {
    }

    private record BrowseKey(CicsTaskId task, String file, int reqid) {
    }

    private static final class Browse {
        private final boolean rrn;
        private boolean generic;
        private boolean equal;
        /** 位置づけに使う鍵の長さ。総称なら短い。 */
        private int keyLength;
        /** 位置づけの鍵。null ならデータセットの終わり (RIDFLD がすべて X'FF')。 */
        private byte[] start;
        /** 直前に返した record の id。位置づけのあとは null。 */
        private byte[] last;
        private boolean previous;
        /** RIDFLD に置いた値。次の読みで違っていれば、利用者が位置を変えたとみなす。 */
        private byte[] echo;
        /** RESETBR が record を見つけられなかった。 */
        private boolean lost;

        private Browse(boolean rrn) {
            this.rrn = rrn;
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
    /** task ごとに、file → 更新のために持つ record の id。 */
    private final Map<CicsTaskId, Map<String, byte[]>> updates = new HashMap<>();
    private final Map<BrowseKey, Browse> browses = new HashMap<>();

    CicsFileControl(List<CicsFileDefinition> definitions) {
        this.definitions = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(CicsFileDefinition::name, Function.identity()));
    }

    @Override
    public OptionalInt keyLengthOf(String file) {
        CicsFileDefinition definition = definitions.get(file);
        return definition == null || definition.relative()
                ? OptionalInt.empty() : OptionalInt.of(definition.keyLength());
    }

    @Override
    public boolean variableLength(String file) {
        CicsFileDefinition definition = definitions.get(file);
        return definition != null && definition.variable();
    }

    // ---- 1 件ずつの命令 ----

    @Override
    public synchronized Result write(CicsTaskId task, String file, byte[] ridfld, boolean rrn, byte[] record) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return FILE_NOT_FOUND;
        }
        if (!definition.allows(CicsFileDefinition.Service.ADD)) {
            throw state("WRITE FILE(" + file + ") is not allowed by the file definition;"
                    + " the INVREQ RESP2 value is not verified");
        }
        Result length = lengthCondition(definition, record);
        if (length != null) {
            return length;
        }
        byte[] id;
        if (definition.relative()) {
            if (!rrn || ridfld.length != Integer.BYTES || ByteBuffer.wrap(ridfld).getInt() < 1) {
                throw state("WRITE FILE(" + file + ") requires RRN with a relative record number of 1 or greater");
            }
            id = ridfld.clone();
        } else {
            if (rrn) {
                throw state("WRITE FILE(" + file + ") RRN applies only to a relative record file");
            }
            requireKeyInRecord(definition, record, "WRITE");
            if (ridfld.length != definition.keyLength() || !Arrays.equals(ridfld, keyOf(definition, record))) {
                // RIDFLD とレコードの鍵が食い違う形は INVREQ だが、RESP2 の値を確かめていない
                throw state("WRITE FILE(" + file + ") RIDFLD does not match the record key");
            }
            id = ridfld.clone();
        }
        return io(() -> {
            Store store = open(definition, true);
            try {
                String written = store.insert(id, record);
                return switch (written) {
                    case FileStatus.OK -> NORMAL;
                    // DUPREC (RESP2 150): 同じ鍵のレコードが既にある
                    case FileStatus.DUPLICATE_KEY -> new Result(CicsResponseCode.DUPREC, 150);
                    // NOSPACE (RESP2 100): 装置に置く場所が無い
                    case FileStatus.BOUNDARY -> new Result(CicsResponseCode.NOSPACE, 100);
                    default -> throw state("WRITE FILE(" + file + ") failed with file status " + written);
                };
            } finally {
                store.close();
            }
        }, failure -> IO_ERROR);
    }

    @Override
    public synchronized Found read(CicsTaskId task, String file, byte[] ridfld, boolean rrn, int keyLength,
                                   boolean generic, boolean gteq, boolean update, Duration maxWait) {
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
        if (update && holding(task, file) != null) {
            // INVREQ (RESP2 28): REWRITE / DELETE / UNLOCK を挟まずに 2 度目の READ UPDATE
            return found(new Result(CicsResponseCode.INVREQ, 28));
        }
        Result invalid = keyLengthCondition(definition, keyLength, generic);
        if (invalid != null) {
            return found(invalid);
        }
        Search search = search(definition, ridfld, rrn, keyLength, generic, gteq, "READ");
        long deadline = deadline(maxWait);
        while (true) {
            Rec record;
            try {
                record = seek(definition, search.id(), search.relation());
            } catch (IoFailure failure) {
                return found(IO_ERROR);
            }
            if (record == null) {
                return found(NOT_FOUND);
            }
            if (!update) {
                return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.id());
            }
            CicsTaskId owner = ownerOf(file, record.id());
            if (owner == null) {
                hold(task, file, record.id());
                return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.id());
            }
            await(deadline, "READ UPDATE FILE(" + file + ")");
        }
    }

    @Override
    public synchronized Result rewrite(CicsTaskId task, String file, byte[] record) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return FILE_NOT_FOUND;
        }
        byte[] held = holding(task, file);
        if (held == null) {
            // INVREQ (RESP2 30): 先に READ UPDATE をしていない
            return new Result(CicsResponseCode.INVREQ, 30);
        }
        Result length = lengthCondition(definition, record);
        if (length != null) {
            return length;
        }
        if (!definition.relative()) {
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
                status = store.replace(held, record);
            } finally {
                store.close();
            }
            return switch (status) {
                case FileStatus.OK -> {
                    release(task, file);
                    yield NORMAL;
                }
                case FileStatus.NO_RECORD -> {
                    release(task, file);
                    // NOTFND (RESP2 80): 読んだあとで record が消えていた
                    yield NOT_FOUND;
                }
                default -> throw state("REWRITE FILE(" + file + ") failed with file status " + status);
            };
        }, failure -> IO_ERROR);
    }

    @Override
    public synchronized Deleted delete(CicsTaskId task, String file, byte[] ridfld, boolean rrn, int keyLength,
                                       boolean generic, Duration maxWait) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return deleted(FILE_NOT_FOUND);
        }
        if (!definition.allows(CicsFileDefinition.Service.DELETE)) {
            // INVREQ (RESP2 20): 定義が削除を許さない
            return deleted(new Result(CicsResponseCode.INVREQ, 20));
        }
        byte[] held = holding(task, file);
        if (ridfld == null) {
            if (held == null) {
                // INVREQ (RESP2 31): RIDFLD の無い DELETE の前に READ UPDATE が無い
                return deleted(new Result(CicsResponseCode.INVREQ, 31));
            }
            return io(() -> {
                Store store = open(definition, true);
                String status;
                try {
                    status = store.remove(held);
                } finally {
                    store.close();
                }
                release(task, file);
                return switch (status) {
                    case FileStatus.OK -> new Deleted(CicsResponseCode.NORMAL, 0, 1);
                    case FileStatus.NO_RECORD -> deleted(NOT_FOUND);
                    default -> throw state("DELETE FILE(" + file + ") failed with file status " + status);
                };
            }, failure -> deleted(IO_ERROR));
        }
        if (held != null) {
            throw state("DELETE FILE(" + file + ") with RIDFLD while the task holds a READ UPDATE"
                    + " on the same file is not verified");
        }
        if (generic && definition.relative()) {
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
        Search search = search(definition, ridfld, rrn, keyLength, generic, false, "DELETE");
        long deadline = deadline(maxWait);
        while (true) {
            List<byte[]> targets;
            try {
                targets = targets(definition, search, generic);
            } catch (IoFailure failure) {
                return deleted(IO_ERROR);
            }
            if (targets.isEmpty()) {
                return deleted(NOT_FOUND);
            }
            if (targets.stream().anyMatch(id -> ownerOf(file, id) != null)) {
                await(deadline, "DELETE FILE(" + file + ")");
                continue;
            }
            return io(() -> {
                Store store = open(definition, true);
                int count = 0;
                try {
                    for (byte[] id : targets) {
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
                return count == 0 ? deleted(NOT_FOUND) : new Deleted(CicsResponseCode.NORMAL, 0, count);
            }, failure -> deleted(IO_ERROR));
        }
    }

    @Override
    public synchronized Result unlock(CicsTaskId task, String file) {
        if (!definitions.containsKey(Objects.requireNonNull(file, "file"))) {
            return FILE_NOT_FOUND;
        }
        release(task, file);
        return NORMAL;
    }

    // ---- browse ----

    @Override
    public synchronized Result startBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                           int keyLength, boolean generic, boolean equal) {
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
        Browse browse = new Browse(rrn);
        Result positioned = position(definition, browse, ridfld, rrn, keyLength, generic, equal, "STARTBR");
        if (positioned.response() == CicsResponseCode.NORMAL) {
            browses.put(key, browse);
        }
        return positioned;
    }

    @Override
    public synchronized Result resetBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                           int keyLength, boolean generic, boolean equal) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return FILE_NOT_FOUND;
        }
        Browse browse = browses.get(new BrowseKey(task, file, reqid));
        if (browse == null) {
            // INVREQ (RESP2 36): REQID か file 名が STARTBR と合わない
            return new Result(CicsResponseCode.INVREQ, 36);
        }
        if (browse.rrn != rrn) {
            // INVREQ (RESP2 37): record の識別の形を STARTBR から変えた
            return new Result(CicsResponseCode.INVREQ, 37);
        }
        Result positioned = position(definition, browse, ridfld, rrn, keyLength, generic, equal, "RESETBR");
        if (positioned.response() == CicsResponseCode.NOTFND) {
            browse.lost = true;
        }
        return positioned;
    }

    @Override
    public synchronized Found readNext(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                       int keyLength) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return found(FILE_NOT_FOUND);
        }
        Browse browse = browses.get(new BrowseKey(task, file, reqid));
        if (browse == null) {
            // INVREQ (RESP2 34): REQID か file 名が STARTBR と合わない
            return found(new Result(CicsResponseCode.INVREQ, 34));
        }
        if (browse.rrn != rrn) {
            // INVREQ (RESP2 37): record の識別の形を STARTBR から変えた
            return found(new Result(CicsResponseCode.INVREQ, 37));
        }
        requirePosition(browse, "READNEXT", file);
        Result invalid = reposition(definition, browse, ridfld, keyLength, "READNEXT");
        if (invalid != null) {
            return found(invalid);
        }
        Rec record;
        try {
            if (browse.last == null) {
                record = browse.start == null ? null
                        : seek(definition, browse.start, browse.equal ? KeyRelation.EQUAL : KeyRelation.NOT_LESS);
            } else if (browse.previous) {
                // 向きを変えると、同じ record をもう一度読む
                record = seek(definition, browse.last, KeyRelation.NOT_LESS);
            } else {
                record = seek(definition, browse.last, KeyRelation.GREATER);
            }
        } catch (IoFailure failure) {
            return found(IO_ERROR);
        }
        if (record == null) {
            return found(END_FILE);
        }
        browse.last = record.id();
        browse.previous = false;
        browse.echo = record.id().clone();
        return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.id());
    }

    @Override
    public synchronized Found readPrevious(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                           int keyLength) {
        CicsFileDefinition definition = definitions.get(Objects.requireNonNull(file, "file"));
        if (definition == null) {
            return found(FILE_NOT_FOUND);
        }
        Browse browse = browses.get(new BrowseKey(task, file, reqid));
        if (browse == null) {
            // INVREQ (RESP2 41): REQID か file 名が STARTBR と合わない
            return found(new Result(CicsResponseCode.INVREQ, 41));
        }
        if (browse.rrn != rrn) {
            // INVREQ (RESP2 37): record の識別の形を STARTBR から変えた
            return found(new Result(CicsResponseCode.INVREQ, 37));
        }
        if (browse.generic) {
            // INVREQ (RESP2 24): GENERIC で始めた browse に READPREV
            return found(new Result(CicsResponseCode.INVREQ, 24));
        }
        requirePosition(browse, "READPREV", file);
        Result invalid = reposition(definition, browse, ridfld, keyLength, "READPREV");
        if (invalid != null) {
            return found(invalid);
        }
        Rec record;
        try {
            if (browse.last == null) {
                if (browse.start == null) {
                    record = seek(definition, highest(definition), KeyRelation.NOT_GREATER);
                } else {
                    record = seek(definition, browse.start, KeyRelation.EQUAL);
                    if (record == null) {
                        // NOTFND (RESP2 80): 直前の STARTBR / RESETBR の RIDFLD に当たる record が無い
                        return found(NOT_FOUND);
                    }
                }
            } else if (!browse.previous) {
                // 向きを変えると、同じ record をもう一度読む
                record = seek(definition, browse.last, KeyRelation.NOT_GREATER);
            } else {
                record = seek(definition, browse.last, KeyRelation.LESS);
            }
        } catch (IoFailure failure) {
            return found(IO_ERROR);
        }
        if (record == null) {
            return found(END_FILE);
        }
        browse.last = record.id();
        browse.previous = true;
        browse.echo = record.id().clone();
        return new Found(CicsResponseCode.NORMAL, 0, record.data(), record.id());
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

    private Result position(CicsFileDefinition definition, Browse browse, byte[] ridfld, boolean rrn, int keyLength,
                            boolean generic, boolean equal, String command) {
        Result invalid = keyLengthCondition(definition, keyLength, generic);
        if (invalid != null) {
            return invalid;
        }
        if (!generic && endOfDataSet(definition, ridfld, rrn)) {
            // すべて X'FF' の RIDFLD は、READPREV のためにデータセットの終わりへ位置づける
            browse.generic = false;
            browse.equal = equal;
            browse.keyLength = identifierLength(definition);
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
        Search search = search(definition, ridfld, rrn, keyLength, generic, !equal, command);
        Rec found;
        try {
            found = seek(definition, search.id(), search.relation());
        } catch (IoFailure failure) {
            return IO_ERROR;
        }
        if (found == null) {
            return NOT_FOUND;
        }
        browse.generic = generic;
        browse.equal = equal;
        browse.keyLength = search.id().length;
        browse.start = search.id();
        browse.last = null;
        browse.previous = false;
        browse.echo = search.id().clone();
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
        if (browse.rrn) {
            if (keyLength != ABSENT) {
                throw state(command + " KEYLENGTH does not apply to a relative record file");
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
        int length = browse.generic ? browse.keyLength : identifierLength(definition);
        requireLength(ridfld, length, command, definition);
        byte[] start = Arrays.copyOf(ridfld, length);
        if (!browse.generic && allHigh(start)) {
            browse.start = null;
            browse.last = null;
            browse.previous = false;
            browse.echo = start;
            return null;
        }
        if (browse.rrn && ByteBuffer.wrap(start).getInt() < 0) {
            throw state(command + " relative record number must not be negative");
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

    private static boolean endOfDataSet(CicsFileDefinition definition, byte[] ridfld, boolean rrn) {
        int length = identifierLength(definition);
        return (rrn == definition.relative()) && ridfld.length >= length && allHigh(Arrays.copyOf(ridfld, length));
    }

    private static boolean allHigh(byte[] bytes) {
        for (byte value : bytes) {
            if (value != (byte) 0xFF) {
                return false;
            }
        }
        return bytes.length > 0;
    }

    private static int identifierLength(CicsFileDefinition definition) {
        return definition.relative() ? Integer.BYTES : definition.keyLength();
    }

    private static byte[] highest(CicsFileDefinition definition) {
        if (definition.relative()) {
            return ByteBuffer.allocate(Integer.BYTES).putInt(Integer.MAX_VALUE).array();
        }
        byte[] key = new byte[definition.keyLength()];
        Arrays.fill(key, (byte) 0xFF);
        return key;
    }

    /** KEYLENGTH と GENERIC の組み合わせの条件。文書に RESP2 のあるものだけ。 */
    private static Result keyLengthCondition(CicsFileDefinition definition, int keyLength, boolean generic) {
        if (definition.relative()) {
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

    /** RIDFLD から位置づけの鍵を作る。keyLengthCondition を通したあとに呼ぶ。 */
    private static Search search(CicsFileDefinition definition, byte[] ridfld, boolean rrn, int keyLength,
                                 boolean generic, boolean gteq, String command) {
        KeyRelation relation = gteq ? KeyRelation.NOT_LESS : KeyRelation.EQUAL;
        if (definition.relative()) {
            if (!rrn) {
                throw state(command + " FILE(" + definition.name() + ") is a relative record file and requires RRN");
            }
            if (generic || keyLength != ABSENT) {
                throw state(command + " GENERIC and KEYLENGTH do not apply to a relative record file");
            }
            requireLength(ridfld, Integer.BYTES, command, definition);
            byte[] id = Arrays.copyOf(ridfld, Integer.BYTES);
            int number = ByteBuffer.wrap(id).getInt();
            if (number < 0 || (number == 0 && !gteq)) {
                throw state(command + " FILE(" + definition.name() + ") relative record number " + number
                        + " is not verified");
            }
            return new Search(id, relation);
        }
        if (rrn) {
            throw state(command + " FILE(" + definition.name() + ") RRN applies only to a relative record file");
        }
        if (generic && keyLength == 0 && !gteq) {
            throw state(command + " FILE(" + definition.name() + ") GENERIC KEYLENGTH(0) requires GTEQ");
        }
        int length = generic ? keyLength : definition.keyLength();
        requireLength(ridfld, length, command, definition);
        return new Search(Arrays.copyOf(ridfld, length), relation);
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

    private void release(CicsTaskId task, String file) {
        Map<String, byte[]> held = updates.get(task);
        byte[] id = held == null ? null : held.remove(file);
        if (id == null) {
            return;
        }
        if (held.isEmpty()) {
            updates.remove(task);
        }
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

    private Rec seek(CicsFileDefinition definition, byte[] id, KeyRelation relation) {
        Store store = open(definition, false);
        try {
            return store.seek(id, relation);
        } finally {
            store.close();
        }
    }

    private List<byte[]> targets(CicsFileDefinition definition, Search search, boolean generic) {
        Store store = open(definition, false);
        try {
            if (!generic) {
                Rec record = store.seek(search.id(), KeyRelation.EQUAL);
                return record == null ? List.of() : List.of(record.id());
            }
            return ((KeyedStore) store).idsWithPrefix(search.id());
        } finally {
            store.close();
        }
    }

    private static Store open(CicsFileDefinition definition, boolean write) {
        return definition.relative() ? new RelativeStore(definition, write) : new KeyedStore(definition, write);
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
            return new Rec(keyOf(definition, data), data);
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
            byte[] buffer = new byte[definition.recordLength()];
            String read = dataSet.read(buffer);
            if (FileStatus.AT_END.equals(read)) {
                return null;
            }
            checked(read, "READ", definition);
            return new Rec(ByteBuffer.allocate(Integer.BYTES).putInt(dataSet.currentNumber()).array(),
                    Arrays.copyOf(buffer, dataSet.lastLength()));
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
}
