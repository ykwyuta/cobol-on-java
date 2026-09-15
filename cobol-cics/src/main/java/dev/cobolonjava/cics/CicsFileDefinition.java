package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CICS の file 定義 (FILE resource) (暫定判断 P-131、P-136)。
 *
 * <p>索引編成 (KSDS) と相対レコード編成 (RRDS) を、固定長か可変長で表す。レコードはバッチの
 * {@code IndexedDataSet} / {@code RelativeDataSet} と同じ形で置くので、ジョブのデータセットとして読める。
 * ESDS は RBA の数え方を公開仕様から決められないので表さない。
 *
 * @param name         {@code FILE('名前')} で指す 1〜8 文字の名前
 * @param path         レコードを置くデータセット
 * @param organization 編成
 * @param keyOffset    KSDS の主鍵の位置 (0 起点)。RRDS では 0
 * @param keyLength    KSDS の主鍵の長さ。RRDS では 0
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
        KSDS, RRDS
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
        if (organization == Organization.KSDS
                && (keyOffset < 0 || keyLength < 1 || keyOffset + keyLength > recordLength)) {
            throw new IllegalArgumentException("key must lie within the record: " + name);
        }
        if (organization == Organization.RRDS && (keyOffset != 0 || keyLength != 0)) {
            throw new IllegalArgumentException("a relative record file has no key: " + name);
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
