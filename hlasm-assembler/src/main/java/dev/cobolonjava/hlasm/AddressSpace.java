package dev.cobolonjava.hlasm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 線形の番地空間。
 *
 * <p>これが調査レポートで「足りない部品 1」と書いたものである。{@code Storage} は
 * 「プログラム 1 個の連続したバイト列」で、{@code DataView} はその上の名前付き範囲である。
 * COBOL と PL/I は項目を<b>名前で</b>指すのでこれで足りるが、HLASM は<b>ベース + 変位</b>で指す。
 * 実行時にあるのは 16 本のレジスタの値と番地だけなので、その下に 1 本の番地体系が要る。
 *
 * <p>区画は実際の {@code byte[]} をそのまま指す。写しを取らないのは、決定 0002 が
 * 「同一領域を複数引数へ渡すエイリアシングを保たなければならない」と定めているからである。
 * 同じ {@code Storage} を指す引数が 2 つあれば、番地空間でも同じ番地に重なる。
 *
 * <p>番地は AMODE 31 とする。上位ビットは番地の一部として使わない。
 */
public final class AddressSpace {

    /** 区画の境目。区画どうしが隣り合って誤って跨がないよう、この大きさに揃える。 */
    private static final int PAGE = 4096;

    /** 最初の区画を置く番地。0 付近を空けておくと、ベースなしの誤った番地がすぐ分かる。 */
    private static final int ORIGIN = 0x00010000;

    /**
     * 番地空間の 1 区画。
     *
     * @param name   診断に出す名前
     * @param base   先頭の番地
     * @param bytes  実体。写しではない
     */
    public record Region(String name, int base, byte[] bytes) {

        public int length() {
            return bytes.length;
        }

        public boolean contains(int address, int length) {
            return address >= base && (long) address + length <= (long) base + bytes.length;
        }
    }

    private final List<Region> regions;

    private AddressSpace(List<Region> regions) {
        this.regions = List.copyOf(regions);
    }

    public List<Region> regions() {
        return regions;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 区画を並べる。同じ {@code byte[]} は 1 度だけ置き、エイリアシングを保つ。 */
    public static final class Builder {

        private final List<Region> regions = new ArrayList<>();
        private int next = ORIGIN;

        /** 既に置いた同じ実体があればその番地を返し、無ければ新しく置く。 */
        public int place(String name, byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            for (Region region : regions) {
                if (region.bytes() == bytes) {
                    return region.base();
                }
            }
            int base = next;
            regions.add(new Region(name, base, bytes));
            next = base + Math.max(PAGE, (bytes.length + PAGE - 1) / PAGE * PAGE);
            return base;
        }

        public AddressSpace build() {
            return new AddressSpace(regions);
        }
    }

    private Region regionOf(int address, int length) {
        for (Region region : regions) {
            if (region.contains(address, length)) {
                return region;
            }
        }
        throw new MachineException(MachineException.ADDRESSING,
                String.format("address %08X (%d byte(s)) is outside every region", address, length));
    }

    public byte get(int address) {
        Region region = regionOf(address, 1);
        return region.bytes()[address - region.base()];
    }

    public void set(int address, byte value) {
        Region region = regionOf(address, 1);
        region.bytes()[address - region.base()] = value;
    }

    public byte[] read(int address, int length) {
        Region region = regionOf(address, length);
        byte[] out = new byte[length];
        System.arraycopy(region.bytes(), address - region.base(), out, 0, length);
        return out;
    }

    public void write(int address, byte[] bytes) {
        Region region = regionOf(address, bytes.length);
        System.arraycopy(bytes, 0, region.bytes(), address - region.base(), bytes.length);
    }

    public int getInt(int address) {
        byte[] b = read(address, 4);
        return (b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
    }

    public void putInt(int address, int value) {
        write(address, new byte[] {(byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value});
    }

    public short getShort(int address) {
        byte[] b = read(address, 2);
        return (short) ((b[0] & 0xFF) << 8 | (b[1] & 0xFF));
    }

    public void putShort(int address, int value) {
        write(address, new byte[] {(byte) (value >> 8), (byte) value});
    }
}
