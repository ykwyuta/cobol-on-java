package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * POINTER の値と記憶域の位置の対応 (設計 85 §6、暫定判断 P-150)。
 *
 * <p>この処理系は記憶域をホストの番地に置かないので、POINTER の 4 byte には実行単位の中で振った番号を置く。
 * 同じ記憶域の同じ位置には同じ番号を返すので、POINTER どうしの比較は位置の比較になる。0 は NULL。
 * 番号はホストの番地ではないので、番地の算術や、別の実行単位へ渡した POINTER は意味を持たない。
 *
 * <p>実行単位は特殊レジスタの置き場 ({@link ProgramContext#registers()}) で見分ける。置き場は文脈の写しの間で
 * 分け合われ、実行単位が終われば対応も一緒に捨てられる。
 */
public final class AddressSpace {

    /** 番号の始まりと間隔。0 (NULL) や小さな値と重ならないようにする。 */
    private static final int FIRST = 0x0001_0000;
    private static final int STEP = 16;

    private static final Map<Storage, AddressSpace> BY_RUN_UNIT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 番号が指す位置。 */
    public record Location(Storage storage, int offset) {
    }

    private final Map<Storage, Map<Integer, Integer>> numbers = new IdentityHashMap<>();
    private final List<Location> locations = new ArrayList<>();

    private AddressSpace() {
    }

    /** 文脈の実行単位の対応。 */
    public static AddressSpace of(ProgramContext context) {
        return BY_RUN_UNIT.computeIfAbsent(context.registers(), ignored -> new AddressSpace());
    }

    /** 記憶域の位置に番号を振る。同じ位置には同じ番号を返す。 */
    public synchronized int addressOf(Storage storage, int offset) {
        Map<Integer, Integer> offsets = numbers.computeIfAbsent(storage, ignored -> new java.util.HashMap<>());
        Integer known = offsets.get(offset);
        if (known != null) {
            return known;
        }
        if (locations.size() >= (Integer.MAX_VALUE - FIRST) / STEP) {
            throw new IllegalStateException("too many addresses in one run unit");
        }
        int address = FIRST + locations.size() * STEP;
        locations.add(new Location(storage, offset));
        offsets.put(offset, address);
        return address;
    }

    /** 番号が指す位置。この実行単位で振った番号でなければ null。 */
    public synchronized Location locate(int address) {
        long index = ((long) address - FIRST) / STEP;
        if (address < FIRST || ((long) address - FIRST) % STEP != 0 || index >= locations.size()) {
            return null;
        }
        return locations.get((int) index);
    }

    /**
     * 入口の番号の始まり。データの番号 ({@link #FIRST} から上へ伸びる) と重ならないよう、
     * 31 bit の番地の上の端に置く。重なると、データを指す POINTER を手続きポインタへ
     * 写して呼んだときに、たまたま当たった入口が動いてしまう。
     */
    private static final int FIRST_ENTRY = 0x7F00_0000;

    private final List<String> entries = new ArrayList<>();

    /** 入口の名前に番号を振る。同じ名前には同じ番号を返す ({@code SET ... TO ENTRY})。 */
    public synchronized int entryOf(String name) {
        String key = name.toUpperCase(java.util.Locale.ROOT);
        int index = entries.indexOf(key);
        if (index < 0) {
            index = entries.size();
            entries.add(key);
        }
        return FIRST_ENTRY + index * STEP;
    }

    /** 番号が指す入口の名前。この実行単位で振った番号でなければ {@code null}。 */
    public synchronized String entryNamed(int number) {
        long index = ((long) number - FIRST_ENTRY) / STEP;
        if (number < FIRST_ENTRY || ((long) number - FIRST_ENTRY) % STEP != 0
                || index >= entries.size()) {
            return null;
        }
        return entries.get((int) index);
    }
}
