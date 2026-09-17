package dev.cobolonjava.ims.psb;

import java.util.List;

/** PSBGEN の {@code PCB} 文 1 つ。 */
public sealed interface PcbDefinition {

    /** {@code PCBNAME=}、無ければ名前欄。どちらも無ければ {@code null}。 */
    String name();

    /**
     * {@code PCB TYPE=DB}。
     *
     * @param dbdName           {@code DBDNAME=}
     * @param processingOptions {@code PROCOPT=} (既定 {@code A})
     * @param keyLength         {@code KEYLEN=}。キー帰還域の長さ
     * @param segments          {@code SENSEG} を書いた順
     */
    record Database(String name, String dbdName, String processingOptions, int keyLength,
                    List<SensitiveSegment> segments) implements PcbDefinition {

        public Database {
            segments = List.copyOf(segments);
        }
    }

    /**
     * {@code PCB TYPE=TP} (代替 PCB)。
     *
     * @param logicalTerminal {@code LTERM=} か {@code NAME=}。宛先を持たなければ {@code null}
     * @param modifiable      {@code MODIFY=YES}
     * @param express         {@code EXPRESS=YES}
     */
    record Terminal(String name, String logicalTerminal, boolean modifiable, boolean express)
            implements PcbDefinition {
    }
}
