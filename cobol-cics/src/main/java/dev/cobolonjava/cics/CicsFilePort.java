package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.IndexedDataSet;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.RecordFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * file control (暫定判断 P-131)。region の構成が持ち、task どうしで分け合う。
 *
 * <p>返す RESP / RESP2 は CICS TS の公開文書で数を確かめたものだけである。
 */
public interface CicsFilePort {

    /** file 命令の結果。 */
    record Result(int response, int response2) {
    }

    /**
     * {@code WRITE FILE}。
     *
     * @param key RIDFLD の先頭 key 長ぶん
     */
    Result write(String file, byte[] key, byte[] record);

    /** 定義を持つ実装。鍵の長さを答える。 */
    interface DataSetFiles extends CicsFilePort, CicsFileKeyLengths {
    }

    /** file を 1 つも定義していない region。どの名前も FILENOTFOUND になる。 */
    static CicsFilePort none() {
        return (file, key, record) -> new Result(CicsResponseCode.FILENOTFOUND, 1);
    }

    /** 定義した file を、バッチと同じ索引編成のデータセットとして持つ。 */
    static CicsFilePort dataSets(List<CicsFileDefinition> definitions) {
        Map<String, CicsFileDefinition> byName = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(CicsFileDefinition::name, Function.identity()));
        return new DataSetFiles() {
            @Override
            public java.util.OptionalInt keyLengthOf(String file) {
                CicsFileDefinition definition = byName.get(file);
                return definition == null ? java.util.OptionalInt.empty()
                        : java.util.OptionalInt.of(definition.keyLength());
            }

            @Override
            public Result write(String file, byte[] key, byte[] record) {
                CicsFileDefinition definition = byName.get(Objects.requireNonNull(file, "file"));
                if (definition == null) {
                    // FILENOTFOUND (RESP2 1): FILE に書いた名前が CICS に定義されていない
                    return new Result(CicsResponseCode.FILENOTFOUND, 1);
                }
                if (record.length != definition.recordLength()) {
                    // 実機は切り詰めか詰め物をして LENGERR (RESP2 14) を返す。その書き方を持たないので失敗させる
                    throw new CicsTaskStateException("WRITE FILE(" + file + ") record length " + record.length
                            + " differs from the fixed length " + definition.recordLength());
                }
                if (key.length != definition.keyLength() || !Arrays.equals(key, Arrays.copyOfRange(record,
                        definition.keyOffset(), definition.keyOffset() + definition.keyLength()))) {
                    // RIDFLD とレコードの鍵が食い違う形は INVREQ だが、RESP2 の値を確かめていない
                    throw new CicsTaskStateException("WRITE FILE(" + file + ") RIDFLD does not match the record key");
                }
                synchronized (definition) {
                    IndexedDataSet dataSet = IndexedDataSet.at(definition.path(),
                            new DataSetAttributes(RecordFormat.FIXED, definition.recordLength(),
                                    definition.codePage()),
                            new IndexedDataSet.Key(definition.keyOffset(), definition.keyLength(), false), List.of());
                    String opened = dataSet.open(OpenMode.IO, true);
                    if (!FileStatus.succeeded(opened)) {
                        throw new CicsTaskStateException("WRITE FILE(" + file + ") cannot open the data set: " + opened);
                    }
                    String written = dataSet.writeKey(record);
                    String closed = dataSet.close();
                    if (!FileStatus.succeeded(closed)) {
                        throw new CicsTaskStateException("WRITE FILE(" + file + ") cannot close the data set: " + closed);
                    }
                    return switch (written) {
                        case FileStatus.OK -> new Result(CicsResponseCode.NORMAL, 0);
                        // DUPREC (RESP2 150): 同じ鍵のレコードが既にある
                        case FileStatus.DUPLICATE_KEY -> new Result(CicsResponseCode.DUPREC, 150);
                        // NOSPACE (RESP2 100): 装置に置く場所が無い
                        case FileStatus.BOUNDARY -> new Result(CicsResponseCode.NOSPACE, 100);
                        default -> throw new CicsTaskStateException(
                                "WRITE FILE(" + file + ") failed with file status " + written);
                    };
                }
            }
        };
    }
}
