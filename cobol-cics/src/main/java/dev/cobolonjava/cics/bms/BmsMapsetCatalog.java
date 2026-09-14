package dev.cobolonjava.cics.bms;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 実行時に SEND MAP / RECEIVE MAP が引く mapset の定義 (設計 79 §8.1)。
 *
 * <p>翻訳時に記号マップ写し句を作ったのと同じ BMS 原文から作る。別の版を渡すと記号マップの
 * byte 位置がずれるので、送る側で長さを照合する ({@link BmsScreenComposer})。
 */
@FunctionalInterface
public interface BmsMapsetCatalog {

    Optional<BmsModel.Mapset> mapset(String name);

    /** 解析済みの mapset から作る。同じ名前が 2 つあれば、どちらを使うか決まらないので断る。 */
    static BmsMapsetCatalog of(Collection<BmsModel.Mapset> mapsets) {
        Objects.requireNonNull(mapsets, "mapsets");
        Map<String, BmsModel.Mapset> byName = new HashMap<>();
        for (BmsModel.Mapset mapset : mapsets) {
            String key = mapset.name().toUpperCase(Locale.ROOT);
            if (byName.putIfAbsent(key, mapset) != null) {
                throw new IllegalArgumentException("duplicate mapset: " + mapset.name());
            }
        }
        Map<String, BmsModel.Mapset> fixed = Map.copyOf(byName);
        return name -> Optional.ofNullable(
                fixed.get(Objects.requireNonNull(name, "name").strip().toUpperCase(Locale.ROOT)));
    }
}
