package dev.cobolonjava.ims.dbd;

/**
 * DBD の {@code ACCESS=} (設計 78 §1.1)。
 *
 * <p>索引で根を持つ方式は根をキーの順に並べる。HDAM / PHDAM は根の並びをランダマイザが決めるので
 * 再現しない (暫定判断 P-102)。ここでは根をキーの順に置き、並びが実機と違いうることを暫定判断に書く。
 */
public enum AccessMethod {
    HDAM(false),
    PHDAM(false),
    HIDAM(true),
    PHIDAM(true),
    HISAM(true),
    SHISAM(true);

    private final boolean keyOrderedRoots;

    AccessMethod(boolean keyOrderedRoots) {
        this.keyOrderedRoots = keyOrderedRoots;
    }

    /** 実機でも根がキーの順に並ぶか。偽なら、無限定の GN の根の順は実機と違いうる。 */
    public boolean keyOrderedRoots() {
        return keyOrderedRoots;
    }

    /** 主索引 (LCHILD POINTER=INDX) を持つ方式か。 */
    public boolean primaryIndexed() {
        return this == HIDAM || this == PHIDAM;
    }
}
