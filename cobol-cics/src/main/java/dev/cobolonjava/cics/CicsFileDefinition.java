package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CICS の file 定義 (FILE resource) (暫定判断 P-131、P-136、P-147)。
 *
 * <p>索引編成 (KSDS)、相対レコード編成 (RRDS)、入力順編成 (ESDS)、BDAM を、固定長か可変長で表す。レコードはバッチの
 * {@code IndexedDataSet} / {@code RelativeDataSet} / {@code SequentialDataSet} と同じ形で置くので、ジョブのデータセットとして読める。
 * ESDS の RBA は、並びの中でその record より前にある record の長さの和とする (CI / CA の制御情報は数えない。設計 85 §5.1)。
 * BDAM は相対レコード編成のデータセットに置き、1 block を 1 record とする (設計 85 §5.4)。
 *
 * @param name         {@code FILE('名前')} で指す 1〜8 文字の名前
 * @param path         レコードを置くデータセット
 * @param organization 編成
 * @param keyOffset    KSDS の主鍵、BDAM の record の鍵の位置 (0 起点)。ほかは 0
 * @param keyLength    KSDS の主鍵、BDAM の record の鍵の長さ。RRDS と ESDS では 0、鍵の無い BDAM でも 0
 * @param recordLength 固定長ならその長さ、可変長なら最大の長さ
 * @param variable     可変長か
 * @param codePage     データセットの属性に書く code page
 * @param services     定義が許す操作。許さない操作は INVREQ (RESP2 20) になる
 */
public record CicsFileDefinition(
        String name, Path path, Organization organization, int keyOffset, int keyLength, int recordLength,
        boolean variable, CodePage codePage, Set<Service> services) {

    /** 編成。 */
    public enum Organization {
        KSDS, RRDS, ESDS, BDAM
    }

    /** file 定義の READ / UPDATE / ADD / DELETE / BROWSE にあたる操作。 */
    public enum Service {
        READ, UPDATE, ADD, DELETE, BROWSE
    }

    private static final Pattern NAME = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    public CicsFileDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(codePage, "codePage");
        services = services.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(services));
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("file name must be 1 to 8 characters: " + name);
        }
        if (recordLength < 1 || recordLength > 32767) {
            throw new IllegalArgumentException("record length must be 1 to 32767: " + name);
        }
        switch (organization) {
            case KSDS -> {
                if (keyOffset < 0 || keyLength < 1 || keyOffset + keyLength > recordLength) {
                    throw new IllegalArgumentException("key must lie within the record: " + name);
                }
            }
            case RRDS -> {
                if (keyOffset != 0 || keyLength != 0) {
                    throw new IllegalArgumentException("a relative record file has no key: " + name);
                }
            }
            case ESDS -> {
                if (keyOffset != 0 || keyLength != 0) {
                    throw new IllegalArgumentException("an entry-sequenced file has no key: " + name);
                }
            }
            case BDAM -> {
                if (keyOffset < 0 || keyLength < 0 || keyOffset + keyLength > recordLength) {
                    throw new IllegalArgumentException("key must lie within the record: " + name);
                }
            }
        }
    }

    /** 固定長の KSDS。すべての操作を許す。 */
    public CicsFileDefinition(String name, Path path, int keyOffset, int keyLength, int recordLength, CodePage codePage) {
        this(name, path, Organization.KSDS, keyOffset, keyLength, recordLength, false, codePage,
                EnumSet.allOf(Service.class));
    }

    /** RRDS。すべての操作を許す。 */
    public static CicsFileDefinition relative(String name, Path path, int recordLength, boolean variable,
                                              CodePage codePage) {
        return new CicsFileDefinition(name, path, Organization.RRDS, 0, 0, recordLength, variable, codePage,
                EnumSet.allOf(Service.class));
    }

    /** ESDS。すべての操作を許す (DELETE は ESDS の規則で INVREQ 21 になる)。 */
    public static CicsFileDefinition entrySequenced(String name, Path path, int recordLength, boolean variable,
                                                    CodePage codePage) {
        return new CicsFileDefinition(name, path, Organization.ESDS, 0, 0, recordLength, variable, codePage,
                EnumSet.allOf(Service.class));
    }

    /** BDAM。keyLength が 0 なら DEBKEY で比べる鍵を持たない。すべての操作を許す (DELETE は INVREQ 27 になる)。 */
    public static CicsFileDefinition directAccess(String name, Path path, int keyOffset, int keyLength,
                                                  int recordLength, boolean variable, CodePage codePage) {
        return new CicsFileDefinition(name, path, Organization.BDAM, keyOffset, keyLength, recordLength, variable,
                codePage, EnumSet.allOf(Service.class));
    }

    /** 可変長にした定義。 */
    public CicsFileDefinition withVariableLength() {
        return new CicsFileDefinition(name, path, organization, keyOffset, keyLength, recordLength, true, codePage,
                services);
    }

    /** 許す操作を替えた定義。 */
    public CicsFileDefinition withServices(Set<Service> value) {
        return new CicsFileDefinition(name, path, organization, keyOffset, keyLength, recordLength, variable, codePage,
                value);
    }

    public boolean relative() {
        return organization == Organization.RRDS;
    }

    public boolean allows(Service service) {
        return services.contains(service);
    }
}
