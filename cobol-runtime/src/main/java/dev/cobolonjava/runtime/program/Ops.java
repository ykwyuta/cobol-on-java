package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.Abend;
import dev.cobolonjava.runtime.abend.AbendCode;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CollatingSequence;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.function.Intrinsics;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.IndexedDataSet;
import dev.cobolonjava.runtime.file.KeyRelation;
import dev.cobolonjava.runtime.file.KeyedDataSet;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.RelativeDataSet;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.sort.SortKey;
import dev.cobolonjava.runtime.sort.SortWork;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.runtime.verb.Arithmetic;
import dev.cobolonjava.runtime.verb.Compare;
import dev.cobolonjava.runtime.verb.Inspect;
import dev.cobolonjava.runtime.verb.InspectScan;
import dev.cobolonjava.runtime.verb.Region;
import dev.cobolonjava.runtime.verb.StringVerb;
import dev.cobolonjava.runtime.verb.UnstringVerb;
import java.util.Arrays;
import java.util.List;
import dev.cobolonjava.runtime.verb.Move;

/**
 * 生成コードが呼ぶ入口 (方針 ARC-7)。
 *
 * <p>意味論そのものは {@link Move} などランタイムの動詞が持つ。ここにあるのは
 * <b>記憶域の位置と動詞をつなぐだけ</b>の薄い層である。生成コードを短く保ち、
 * バイトコードの誤りが入り込む余地を減らすために置いている。
 */
public final class Ops {

    private Ops() {
    }

    /** デバッグの節を動かすか (要件 FR-193)。実行時の切り替えである。 */
    public static boolean debuggingProcedures(ProgramContext context) {
        return context.debuggingProcedures();
    }

    /** 記憶域の一部を取り出す。 */
    public static byte[] read(Storage storage, int offset, int length) {
        return storage.view(offset, length).toByteArray();
    }

    /** 英数字転記。 */
    public static void moveAlphanumeric(byte[] source, Storage storage, int offset, int length,
                                        boolean justifiedRight, CodePage codePage) {
        storage.view(offset, length)
                .setBytes(Move.alphanumeric(source, length, justifiedRight, codePage));
    }

    /** 数値転記。 */
    public static void moveNumeric(Decimal value, NumericItem target, Storage storage, int offset) {
        Move.numeric(value, target, storage.view(offset, target.byteLength()));
    }

    /** 数字編集項目への転記。 */
    public static void moveNumericEdited(Decimal value, Picture target, Storage storage, int offset,
                                         CodePage codePage) {
        Move.toNumericEdited(value, target, storage.view(offset, target.size()), codePage);
    }

    /** 英数字編集項目への転記。 */
    public static void moveAlphanumericEdited(byte[] source, Picture target, Storage storage,
                                              int offset, CodePage codePage) {
        Move.toAlphanumericEdited(source, target, storage.view(offset, target.size()), codePage);
    }

    /**
     * 符号を落とした数字の並びを読む (要件 FR-060)。
     *
     * <p>符号付きの表示形式の項目を英数字項目へ転記するとき、規格は<b>絶対値</b>を
     * 送るものと決めている。ゾーンに埋め込んだ符号も、別に持つ 1 バイトの符号も、
     * 送出データには入らない。そのまま読むと最後の桁が英字に見える。
     */
    public static byte[] readUnsignedDigits(NumericItem source, Storage storage, int offset,
                                            CodePage codePage) {
        byte[] raw = storage.view(offset, source.byteLength()).toByteArray();
        SignPosition sign = source.signPosition();
        if (!sign.isSigned()) {
            return raw;
        }
        if (sign.isSeparate()) {
            // 符号だけの 1 バイトを落とす
            return sign.isLeading()
                    ? Arrays.copyOfRange(raw, 1, raw.length)
                    : Arrays.copyOfRange(raw, 0, raw.length - 1);
        }
        // ゾーンに埋め込んだ符号は、数字のゾーンへ戻す。
        // <b>値としては読まない。</b>読むと、数字が入っていない項目で止まってしまう。
        // 参照実装も 1 命令でゾーンを塗り替えるだけであり、中身を確かめはしない
        int at = sign.isLeading() ? 0 : raw.length - 1;
        byte[] out = raw.clone();
        out[at] = (byte) ((codePage.zoneNibble() << 4) | (out[at] & 0x0F));
        return out;
    }

    /** 数値項目の読み出し。 */
    public static Decimal readNumeric(NumericItem source, Storage storage, int offset) {
        DataView view = storage.view(offset, source.byteLength());
        return source.load(view);
    }

    /**
     * 繰り返しの回数として読む。
     *
     * <p>小数部は切り捨てる。{@code PERFORM n TIMES} の {@code n} は整数項目であることを
     * 規格が求めているため、切り捨てが起きるのは規格外のソースだけである。
     */
    public static int toInt(Decimal value) {
        return value.toBigDecimal().intValue();
    }

    /**
     * {@code STOP RUN} と {@code GOBACK}。実行を終える。
     *
     * <p>段落は別々のメソッドとして生成されるため、単に戻るだけでは呼び出し元へ
     * 制御が返ってしまう。<b>例外で一気に抜ける</b>ことで、どこから呼ばれていても
     * プログラムの実行そのものが終わる。
     */
    public static void stopRun() {
        throw new ProgramStop();
    }

    /**
     * {@code GOBACK} と手続き部の終わり。<b>呼んだ側へ戻る</b> (要件 FR-067)。
     *
     * <p>{@link #stopRun()} との違いはどこまで抜けるかである。副プログラムから投げれば
     * 呼んだ側が受け止めて続きを実行し、主プログラムなら実行の終わりになる。
     */
    public static void programReturn() {
        throw new ProgramReturn();
    }

    /**
     * {@code EXIT PROGRAM} (要件 FR-067)。
     *
     * <p>呼ばれていれば呼んだ側へ戻る。<b>主プログラムなら何もしない</b>。COBOL の
     * 決まりがそうなっており、次の文へ進む。
     *
     * <p>{@code GOBACK} との違いはここだけである。{@code GOBACK} は主プログラムなら
     * 実行を終える。同じプログラムが呼ばれることも主として動くこともあるので、
     * <b>どちらの意味になるかは実行時にしか分からない</b>。
     *
     * <p>積まれているプログラムが 1 つなら主である。{@code CALL} は積むので、
     * 呼ばれていれば 2 つ以上になる。
     */
    public static void exitProgram(ProgramContext context) {
        if (context.active().size() > 1) {
            throw new ProgramReturn();
        }
    }

    // ---- 表示 ----

    /**
     * {@code DISPLAY} の 1 項目。
     *
     * @param advancing 行を改めるかどうか
     */
    public static void display(byte[] bytes, ProgramContext context, boolean advancing,
                               boolean toError) {
        context.display(bytes, advancing, toError);
    }

    public static void display(byte[] bytes, ProgramContext context, boolean advancing) {
        context.display(bytes, advancing);
    }

    /**
     * 数値を表示の形へ直す。
     *
     * <p>{@code COMP} や {@code COMP-3} の項目をそのまま出しても読めない。
     * 同じ桁数・同じ小数部の {@code DISPLAY} 項目として符号化し直す。
     */
    public static byte[] displayForm(Decimal value, NumericItem shape) {
        return shape.encode(value);
    }

    // ---- STRING / UNSTRING ----

    /**
     * {@code STRING}。つないだ結果を受取項目へ書き、実行結果を返す。
     *
     * <p><b>受取項目の残りは埋めない</b>。書いた分だけが変わる。
     */
    public static StringVerb.Result string(Storage storage, int offset, int length, int pointer,
                                           StringVerb.Source... sources) {
        StringVerb.Result result = StringVerb.string(read(storage, offset, length), pointer,
                List.of(sources));
        storage.view(offset, length).setBytes(result.target());
        return result;
    }

    /** {@code UNSTRING}。転記は結果から取り出して行う。 */
    public static UnstringVerb.Result unstring(Storage storage, int offset, int length,
                                               int pointer, UnstringVerb.Delimiter[] delimiters,
                                               UnstringVerb.Field[] fields, CodePage codePage) {
        return UnstringVerb.unstring(read(storage, offset, length), pointer,
                List.of(delimiters), List.of(fields), codePage);
    }

    /** {@code UNSTRING} の受取項目 1 個。転記が行われなかった項目は変えない。 */
    public static void storeUnstringField(UnstringVerb.Result result, int index, Storage storage,
                                          int offset, int length, boolean justifiedRight,
                                          CodePage codePage) {
        if (index < result.fields().size()) {
            moveAlphanumeric(result.fields().get(index), storage, offset, length,
                    justifiedRight, codePage);
        }
    }

    /**
     * {@code UNSTRING} の受取項目が数字項目のとき (要件 FR-060)。
     *
     * <p>切り出したものを<b>符号なし整数</b>として読み、小数点で位置を合わせて入れる。
     * 英数字として左から詰めると、桁があふれたときに<b>上の桁</b>が残ってしまう。
     * "12" を {@code PIC 9} へ入れると 2 である (NC218A の UST-TEST-GF-5)。
     */
    /**
     * 数を<b>足し込む</b> (要件 FR-060)。
     *
     * <p>{@code UNSTRING ... TALLYING} は、受取項目のいまの値に「入れた項目の数」を
     * 足す。入れ替えるのではない。規格がそう決めている (NC218A の UST-TEST-GF-20)。
     */
    public static void addInteger(int value, NumericItem target, Storage storage, int offset) {
        DataView view = storage.view(offset, target.byteLength());
        target.store(view, target.load(view).add(Decimal.of(
                java.math.BigInteger.valueOf(Math.abs(value)), 0, value < 0 ? -1 : 1)));
    }

    public static void storeUnstringNumeric(UnstringVerb.Result result, int index,
                                            NumericItem target, Storage storage, int offset,
                                            CodePage codePage) {
        if (index < result.fields().size()) {
            moveNumeric(asInteger(result.fields().get(index), codePage), target, storage, offset);
        }
    }

    /** {@code DELIMITER IN} の受取項目。 */
    public static void storeUnstringDelimiter(UnstringVerb.Result result, int index,
                                              Storage storage, int offset, int length,
                                              boolean justifiedRight, CodePage codePage) {
        if (index < result.delimiters().size()) {
            moveAlphanumeric(result.delimiters().get(index), storage, offset, length,
                    justifiedRight, codePage);
        }
    }

    /** {@code COUNT IN} の受取項目。 */
    public static void storeUnstringCount(UnstringVerb.Result result, int index,
                                          NumericItem counter, Storage storage, int offset) {
        if (index < result.counts().size()) {
            storeInteger(result.counts().get(index), counter, storage, offset);
        }
    }

    /** 数値項目を {@code int} として読む。長さそのものが値である場面で使う。 */
    public static int readInteger(NumericItem source, Storage storage, int offset) {
        return readNumeric(source, storage, offset).toBigDecimal().intValue();
    }

    /** 整数を数値項目へ入れる。{@code POINTER} や {@code TALLYING} が使う。 */
    public static void storeInteger(int value, NumericItem target, Storage storage, int offset) {
        store(Decimal.of(value, 0), target, storage, offset, CobolRounding.TRUNCATION);
    }

    // ---- INSPECT ----

    /** 検査する範囲。指定のない側は {@code null} を渡す。 */
    public static Region region(byte[] after, byte[] before) {
        if (after == null && before == null) {
            return Region.whole();
        }
        if (before == null) {
            return Region.after(after);
        }
        return after == null ? Region.before(before) : Region.between(after, before);
    }

    /** 数える走査。返るのは句ごとの計数である。 */
    public static int[] tally(Storage storage, int offset, int length,
                              InspectScan.Clause... clauses) {
        return InspectScan.tally(read(storage, offset, length), clauses);
    }

    /** 置き換える走査。 */
    public static void replace(Storage storage, int offset, int length,
                               InspectScan.Clause... clauses) {
        storage.view(offset, length)
                .setBytes(InspectScan.replace(read(storage, offset, length), clauses));
    }

    /** {@code CONVERTING}。1 バイトずつの読み替えである。 */
    public static void convert(Storage storage, int offset, int length, byte[] from, byte[] to,
                               Region region) {
        storage.view(offset, length)
                .setBytes(Inspect.convert(read(storage, offset, length), from, to, region));
    }

    /** 数えた結果を計数の項目へ足し込む。 */
    public static void addTally(int count, NumericItem counter, Storage storage, int offset) {
        Decimal current = readNumeric(counter, storage, offset);
        store(current.add(Decimal.of(count, 0)), counter, storage, offset,
                CobolRounding.TRUNCATION);
    }

    // ---- 比較 ----

    /** 数値比較。内部表現と桁数の違いに影響されない。 */
    public static int compareNumeric(Decimal left, Decimal right) {
        return Compare.numeric(left, right);
    }

    /**
     * {@code FUNCTION CURRENT-DATE} (要件 FR-070、テスト時の固定は FR-204)。
     *
     * <p>時計は {@link ProgramContext} が持っている。実行のたびに変わる値を試験に
     * 書けるようにするためである。
     */
    public static byte[] currentDate(ProgramContext context) {
        return Intrinsics.timestamp(java.time.ZonedDateTime.now(context.clock()),
                context.codePage());
    }

    /**
     * {@code FUNCTION RANDOM} (要件 FR-070)。
     *
     * <p>0 以上 1 未満を返す。並びは {@link ProgramContext} が持っている。
     */
    public static Decimal random(ProgramContext context) {
        return Intrinsics.randomValue(context.nextRandom());
    }

    /** {@code FUNCTION RANDOM(種)}。種を決めてから最初の 1 つを返す。 */
    public static Decimal random(Decimal seed, ProgramContext context) {
        context.seedRandom(seed.toBigDecimal().longValue());
        return Intrinsics.randomValue(context.nextRandom());
    }

    /**
     * 段落へ入るところで、段分けの独立段を初期状態へ戻す (要件 FR-061)。
     *
     * <p>段番号 50 以上は<b>独立段</b>である。別の段から制御が移るたびに初期状態へ戻る。
     * 「初期状態」とは <b>{@code ALTER} で書き換えた飛び先が元へ戻る</b>ことであり、
     * 記憶域の中身は戻らない。したがって {@code ALTER} を実装してはじめて意味を持つ。
     *
     * @param altered  いまの飛び先。書き換えられる段落だけが 0 以上を持つ
     * @param initial  書かれたままの飛び先
     * @param segment  段落ごとの段番号
     * @param entering これから動かす段落の番号
     * @param current  いままで動いていた段の番号。まだ動いていなければ {@code -1}
     * @return これから動く段の番号
     */
    public static int enterParagraph(int[] altered, int[] initial, int[] segment, int entering,
                                     int current) {
        int next = segment[entering];
        if (next >= INDEPENDENT_SEGMENT && next != current) {
            for (int i = 0; i < altered.length; i++) {
                if (segment[i] == next && initial[i] >= 0) {
                    altered[i] = initial[i];
                }
            }
        }
        return next;
    }

    /** ここから上が独立段である。 */
    private static final int INDEPENDENT_SEGMENT = 50;

    /** 英数字比較。短いほうは空白で埋めて比べる。 */
    public static int compareAlphanumeric(byte[] left, byte[] right, CodePage codePage) {
        return Compare.alphanumeric(left, right, codePage);
    }

    /**
     * 照合順序を差し替えた英数字比較 (要件 FR-054)。
     *
     * <p>{@code PROGRAM COLLATING SEQUENCE} が書かれているときだけこちらを通る。
     * 書かれていなければコードページのバイト値がそのまま並びなので、上の形でよい。
     */
    public static int compareAlphanumeric(byte[] left, byte[] right, CollatingSequence order,
                                          CodePage codePage) {
        return order.compare(left, right, codePage.space());
    }

    // ---- ファイル入出力 ----

    /**
     * {@code OPEN} (要件 FR-102)。
     *
     * @return ファイル状態コードのバイト列。2 バイトである
     */
    public static byte[] open(ProgramContext context, String name, String ddName, int mode,
                              int organization, int format, int recordLength, boolean optional) {
        if (context.isFileLocked(name)) {
            return status(context, FileStatus.CLOSED_WITH_LOCK);
        }
        Organization kind = Organization.values()[organization];
        return status(context, context.file(name, ddName, kind,
                RecordFormat.values()[format], recordLength)
                .open(requested(context, ddName, OpenMode.values()[mode], kind), optional));
    }

    /**
     * 実際に開く向き (要件 FR-133)。
     *
     * <p>ふつうはプログラムが書いたとおりである。ジョブが {@code DISP=MOD} と言っている
     * ときだけ、{@code OUTPUT} が<b>末尾への書き足し</b>になる。ジョブの指定がプログラムの
     * 書いたことを覆す数少ない場所であり、順編成にしか意味がない。
     */
    private static OpenMode requested(ProgramContext context, String ddName, OpenMode mode,
                                      Organization organization) {
        boolean sequential = organization == Organization.SEQUENTIAL
                || organization == Organization.LINE_SEQUENTIAL;
        return mode == OpenMode.OUTPUT && sequential && context.catalog().appends(ddName)
                ? OpenMode.EXTEND
                : mode;
    }

    /**
     * {@code READ} (要件 FR-102)。読んだレコードは記憶域へ直に書き込む。
     *
     * @return ファイル状態コード
     */
    public static byte[] read(ProgramContext context, String name, String ddName,
                              Storage storage, int offset, int length) {
        DataSet file = context.file(name, ddName);
        byte[] record = new byte[length];
        String status = file.read(record);
        if (FileStatus.succeeded(status)) {
            // 読めなかったときにレコード領域を触らないのは、前の内容が残る規則のためである。
            // 可変長では読めた分だけを入れる。その先の中身は規格上決まっていない (要件 FR-106)
            int copied = file.attributes().format() == RecordFormat.VARIABLE
                    ? file.lastLength()
                    : length;
            storage.view(offset, copied).setBytes(Arrays.copyOf(record, copied));
        }
        return status(context, status);
    }

    /**
     * {@code WRITE} (要件 FR-102, FR-106)。
     *
     * @param length  書き出す長さ。可変長では {@code DEPENDING ON} の項目の値である
     * @param minimum 宣言された下限
     * @param maximum 宣言された上限。レコード領域より長くはならない
     */
    public static byte[] write(ProgramContext context, String name, String ddName,
                               Storage storage, int offset, int length, int minimum,
                               int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                context.file(name, ddName).write(read(storage, offset, actual)), actual, length));
    }

    /** 頁の先頭へ送ることを表す行数。行数と同じ引数に載せるための負の値である。 */
    public static final int PAGE = -1;

    /**
     * 行送りを伴う {@code WRITE} (要件 FR-102)。
     *
     * <p>印字するファイルは<b>行を送ってから書く</b>か、<b>書いてから送る</b>。
     * {@code AFTER ADVANCING 2 LINES} なら 1 行空けてから書く。2 行送って印字するとき、
     * 実際に文字が乗るのは<b>最後の 1 行だけ</b>だからである。
     *
     * <h2>行送りは空のレコードで表す</h2>
     * <p>ホストの印字ファイルはレコードの先頭に紙送りの制御文字を持つ。その形を真似れば
     * バイト列まで合うが、桁の取り方を実機で確かめていない。確かめないまま 1 バイト
     * 増やすと<b>レコードの長さが全部ずれる</b>ので、いまは空のレコードを足すほうを
     * 採った (暫定判断 P-063)。
     *
     * @param lines 送る行数。{@link #PAGE} なら頁の先頭へ送る
     * @param before 書いてから送るか。{@code false} なら送ってから書く
     * @return ファイル状態コード
     */
    public static byte[] writeLine(ProgramContext context, String name, String ddName,
                                   Storage storage, int offset, int length, int minimum,
                                   int maximum, int lines, boolean before) {
        int actual = clamp(length, minimum, maximum);
        DataSet file = context.file(name, ddName);
        byte[] record = read(storage, offset, actual);
        String status = before ? FileStatus.OK : advance(file, lines, actual);
        if (status.equals(FileStatus.OK)) {
            status = file.write(record);
        }
        if (before && status.equals(FileStatus.OK)) {
            status = advance(file, lines, actual);
        }
        return status(context, lengthChecked(status, actual, length));
    }

    /**
     * 論理頁を数えながら書く (要件 FR-113)。
     *
     * <p>{@code LINAGE} を書いたファイルは、紙 1 枚を「上の余白・本文・下の余白」に
     * 分けて扱う。{@code LINAGE-COUNTER} が数えるのは<b>本文の何行目か</b>だけである。
     *
     * <h2>数え方は検査スイートが決めている</h2>
     * <p>NIST CCVS85 の SQ201M が、規格の要求を実行できる形で書いている。そこから
     * 読み取れる規則は 3 つである。
     *
     * <ul>
     *   <li>1 回の {@code WRITE} が使う行数は、{@code ADVANCING n} なら {@code n}、
     *       行送りを書かなければ 1 である。<b>{@code BEFORE} でも {@code AFTER} でも
     *       同じだけ進む</b> (WRT-TEST-004 / 005 / 006)</li>
     *   <li>{@code ADVANCING PAGE} のあと {@code LINAGE-COUNTER} は 1 である
     *       (WRT-TEST-002)</li>
     *   <li>本文をはみ出す書き込みは<b>次の頁の 1 行目</b>へ回り、
     *       {@code LINAGE-COUNTER} は 1 になる (WRT-TEST-003)</li>
     * </ul>
     *
     * <p>頁の終わり ({@code AT END-OF-PAGE}) は、書いたあとの {@code LINAGE-COUNTER} が
     * <b>脚注の行に達したとき</b>に起きる。脚注を書いていなければ、本文をはみ出したとき
     * である。規格がそう分けている。
     *
     * <p>行送りそのものは空のレコードで表す (暫定判断 P-063)。紙送りの制御文字を
     * 実機で確かめていないためである。
     *
     * <p>頁の形は<b>置き場から読む</b>。項目で書けるので、開くたびに読み直された値が
     * そこに入っている。翻訳時に決まるのは置き場だけである。
     *
     * @param counterAt {@code LINAGE-COUNTER} の記憶域上の位置 (2 進 4 バイト)
     * @param pageAt    本文の行数の置き場
     * @param footingAt 脚注が始まる行の置き場。書かれていなければ 0 が入っている
     * @param topAt     上の余白の行数の置き場
     * @param bottomAt  下の余白の行数の置き場
     */
    public static byte[] writeLinage(ProgramContext context, String name, String ddName,
                                     Storage storage, int offset, int length, int minimum,
                                     int maximum, int lines, boolean before,
                                     int counterAt, int pageAt, int footingAt, int topAt,
                                     int bottomAt) {
        int page = Math.max(1, readCounter(storage, pageAt));
        int footing = readCounter(storage, footingAt);
        int top = readCounter(storage, topAt);
        int bottom = readCounter(storage, bottomAt);
        int actual = clamp(length, minimum, maximum);
        DataSet file = context.file(name, ddName);
        byte[] record = read(storage, offset, actual);
        int counter = readCounter(storage, counterAt);
        String status = FileStatus.OK;
        if (counter == 0) {
            // まだ 1 行も置いていない頁である。上の余白を先に送る
            status = blanks(file, top, actual);
        }
        int used = lines == PAGE ? 1 : lines;
        // 頁送りは書かれたとおりの送りであって、はみ出しではない。
        // すでに 1 行も置いていない頁にいるなら、送る先はいまの頁である
        boolean turning = lines == PAGE ? counter > 0 : counter + used > page;
        boolean overflow = lines != PAGE && turning;
        if (turning && status.equals(FileStatus.OK)) {
            status = endPage(file, counter, page, bottom, top, actual);
            counter = 0;
            used = 1;
        }
        if (status.equals(FileStatus.OK)) {
            status = before
                    ? placeBefore(file, record, used, actual)
                    : placeAfter(file, record, used, actual);
        }
        counter += used;
        writeCounter(storage, counterAt, counter);
        context.setEndOfPage(footing > 0 ? counter >= footing : overflow);
        return status(context, lengthChecked(status, actual, length));
    }

    /** 書いてから送る。レコードはいまの行に乗り、残りは空行である。 */
    private static String placeBefore(DataSet file, byte[] record, int used, int width) {
        String status = file.write(record);
        return status.equals(FileStatus.OK) ? blanks(file, used - 1, width) : status;
    }

    /** 送ってから書く。レコードは送った先の行に乗る。 */
    private static String placeAfter(DataSet file, byte[] record, int used, int width) {
        String status = blanks(file, used - 1, width);
        return status.equals(FileStatus.OK) ? file.write(record) : status;
    }

    /** 本文の残りと下の余白を送り、次の頁の上の余白まで進める。 */
    private static String endPage(DataSet file, int counter, int page, int bottom, int top,
                                  int width) {
        String status = blanks(file, page - counter, width);
        if (status.equals(FileStatus.OK)) {
            status = blanks(file, bottom, width);
        }
        return status.equals(FileStatus.OK) ? blanks(file, top, width) : status;
    }

    /** 空行を {@code count} 行送る。 */
    private static String blanks(DataSet file, int count, int width) {
        String status = FileStatus.OK;
        for (int i = 0; i < count && status.equals(FileStatus.OK); i++) {
            status = file.write(blankLine(file, width));
        }
        return status;
    }

    /** {@code LINAGE-COUNTER} を読む。2 進 4 バイトである。 */
    private static int readCounter(Storage storage, int at) {
        byte[] bytes = storage.array();
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    /** {@code LINAGE-COUNTER} を書く。 */
    public static void writeCounter(Storage storage, int at, int value) {
        byte[] bytes = storage.array();
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }

    /** 直前の {@code WRITE} が頁の終わりに達したか ({@code AT END-OF-PAGE} の分岐に使う)。 */
    public static boolean atEndOfPage(ProgramContext context) {
        return context.endOfPage();
    }

    /**
     * 行を送る。
     *
     * <p>{@code n} 行送って印字するなら、間に空くのは {@code n-1} 行である。
     * 送らない ({@code 0} 行) は重ね印字であり、紙の上でしか起こらない。ここでは
     * 空行を足さないだけになる (暫定判断 P-063)。
     *
     * <h2>負の行数は「改頁してから送る」である</h2>
     * <p>{@link #PAGE} は {@code -1} であり、「改頁して 1 行目へ」を表す。これを
     * <b>一般化して</b>、{@code -k} を「改頁して k 行目へ」とする。報告書作成機能
     * (要件 FR-214) が使う。改頁と行送りを 1 回の書き込みで表せるので、頁の先頭に
     * 余計な空行が出ない。
     */
    private static String advance(DataSet file, int lines, int width) {
        if (lines < 0) {
            String status = file.write(pageBreak(file, width));
            for (int i = 1; i < -lines && status.equals(FileStatus.OK); i++) {
                status = file.write(blankLine(file, width));
            }
            return status;
        }
        String status = FileStatus.OK;
        for (int i = 1; i < lines && status.equals(FileStatus.OK); i++) {
            status = file.write(blankLine(file, width));
        }
        return status;
    }

    /**
     * 空行のバイト列。
     *
     * <p>行の並びなら<b>長さ 0</b> が空行である。決まった長さのレコードなら空白で埋める。
     * 行の切れ目を決めているのはデータセットの様式であり、そこに合わせる。
     */
    private static byte[] blankLine(DataSet file, int width) {
        if (file.attributes().format() == RecordFormat.LINE) {
            return new byte[0];
        }
        byte[] blank = new byte[Math.max(width, 0)];
        Arrays.fill(blank, file.attributes().codePage().space());
        return blank;
    }

    /**
     * 改頁のバイト列。
     *
     * <p>紙送りの制御文字を持たないので、<b>改頁の文字だけの行</b>を置く。読み返した
     * ときに頁の切れ目がどこにあったか分かる形である (暫定判断 P-063)。
     */
    private static byte[] pageBreak(DataSet file, int width) {
        byte[] line = blankLine(file, width);
        byte[] out = line.length > 0 ? line : new byte[1];
        out[0] = file.attributes().codePage().encode("\f")[0];
        return out;
    }

    /**
     * {@code REWRITE} (要件 FR-102)。直前に読んだレコードを書き換える。
     *
     * @return ファイル状態コード
     */
    public static byte[] rewrite(ProgramContext context, String name, String ddName,
                                 Storage storage, int offset, int length, int minimum,
                                 int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                context.file(name, ddName).rewrite(read(storage, offset, actual)), actual, length));
    }

    /**
     * 宣言された範囲へ収める (要件 FR-106)。
     *
     * <p>{@code DEPENDING ON} の項目に範囲外の値が入っていることはありうる。そのまま使えば
     * <b>レコード領域の外を読み書きする</b>。収めたうえで、食い違ったことを状態コードで知らせる。
     */
    private static int clamp(int length, int minimum, int maximum) {
        return Math.max(minimum, Math.min(length, maximum));
    }

    /** 長さを収めたなら {@code 04} を立てる。成功しているときだけである。 */
    private static String lengthChecked(String status, int actual, int requested) {
        return actual != requested && FileStatus.succeeded(status)
                ? FileStatus.LENGTH_MISMATCH
                : status;
    }

    /**
     * 索引編成のファイルを開く (要件 FR-100, FR-101)。
     *
     * <p>鍵の場所を渡す。{@code keys} は 3 つずつの組であり、位置・長さ・重複を許すかの順に
     * 並ぶ。先頭の組が主鍵である。
     */
    public static byte[] openIndexed(ProgramContext context, String name, String ddName, int mode,
                                     int format, int recordLength, boolean optional, int[] keys) {
        if (context.isFileLocked(name)) {
            return status(context, FileStatus.CLOSED_WITH_LOCK);
        }
        List<IndexedDataSet.Key> described = new java.util.ArrayList<>();
        for (int at = 0; at + 2 < keys.length; at += 3) {
            described.add(new IndexedDataSet.Key(keys[at], keys[at + 1], keys[at + 2] != 0));
        }
        return status(context, context.file(name, ddName, RecordFormat.values()[format],
                recordLength, described).open(OpenMode.values()[mode], optional));
    }

    /**
     * 鍵で読む (要件 FR-101)。索引編成だけである。
     *
     * <p>鍵の値は<b>レコード領域の中にある</b>。プログラムが鍵の項目へ入れてから読む。
     *
     * @param keyIndex {@code 0} が主鍵、{@code 1} 以降が副鍵の並び順
     */
    public static byte[] readKey(ProgramContext context, String name, String ddName, int keyIndex,
                                 Storage storage, int keyOffset, int keyLength, int offset,
                                 int length) {
        IndexedDataSet file = indexed(context, name, ddName);
        byte[] record = new byte[length];
        String status = file.readKey(keyIndex, read(storage, keyOffset, keyLength), record);
        if (FileStatus.succeeded(status)) {
            int copied = file.attributes().format() == RecordFormat.VARIABLE
                    ? file.lastLength()
                    : length;
            storage.view(offset, copied).setBytes(Arrays.copyOf(record, copied));
        }
        return status(context, status);
    }

    /** 鍵で引いて書く (要件 FR-101)。鍵はレコードの中にある。 */
    public static byte[] writeKey(ProgramContext context, String name, String ddName,
                                  Storage storage, int offset, int length, int minimum,
                                  int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                indexed(context, name, ddName).writeKey(read(storage, offset, actual)),
                actual, length));
    }

    /** 鍵で引いて書き換える (要件 FR-101)。 */
    public static byte[] rewriteKey(ProgramContext context, String name, String ddName,
                                    Storage storage, int offset, int length, int minimum,
                                    int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                indexed(context, name, ddName).rewriteKey(read(storage, offset, actual)),
                actual, length));
    }

    /** 鍵で引いて消す (要件 FR-101)。 */
    public static byte[] deleteKey(ProgramContext context, String name, String ddName,
                                   Storage storage, int keyOffset, int keyLength) {
        return status(context, indexed(context, name, ddName)
                .deleteKey(read(storage, keyOffset, keyLength)));
    }

    /** 鍵で位置だけを決める (要件 FR-101)。 */
    public static byte[] startKey(ProgramContext context, String name, String ddName,
                                  int keyIndex, Storage storage, int keyOffset, int keyLength,
                                  int relation) {
        return status(context, indexed(context, name, ddName).start(keyIndex,
                read(storage, keyOffset, keyLength), KeyRelation.values()[relation]));
    }

    private static IndexedDataSet indexed(ProgramContext context, String name, String ddName) {
        return (IndexedDataSet) context.file(name, ddName);
    }

    /**
     * 番号で読む (要件 FR-101)。相対編成だけである。
     *
     * @param number 相対レコード番号 (1 起点)
     */
    public static byte[] readAt(ProgramContext context, String name, String ddName, int number,
                                Storage storage, int offset, int length) {
        RelativeDataSet file = relative(context, name, ddName);
        byte[] record = new byte[length];
        String status = file.readAt(number, record);
        if (FileStatus.succeeded(status)) {
            storage.view(offset, length).setBytes(record);
        }
        return status(context, status);
    }

    /** 番号を指定して書く (要件 FR-101)。 */
    public static byte[] writeAt(ProgramContext context, String name, String ddName, int number,
                                 Storage storage, int offset, int length, int minimum,
                                 int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                relative(context, name, ddName).writeAt(number, read(storage, offset, actual)),
                actual, length));
    }

    /** 番号を指定して書き換える (要件 FR-101)。 */
    public static byte[] rewriteAt(ProgramContext context, String name, String ddName, int number,
                                   Storage storage, int offset, int length, int minimum,
                                   int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                relative(context, name, ddName).rewriteAt(number, read(storage, offset, actual)),
                actual, length));
    }

    /** 直前に読んだレコードを消す (要件 FR-102)。鍵で引く編成だけである。 */
    public static byte[] delete(ProgramContext context, String name, String ddName) {
        return status(context, ((KeyedDataSet) context.file(name, ddName)).delete());
    }

    /** 番号を指定して消す (要件 FR-101)。 */
    public static byte[] deleteAt(ProgramContext context, String name, String ddName, int number) {
        return status(context, relative(context, name, ddName).deleteAt(number));
    }

    /**
     * 位置だけを決める (要件 FR-101)。
     *
     * @param relation {@link KeyRelation} の並び順
     */
    public static byte[] start(ProgramContext context, String name, String ddName, int number,
                               int relation) {
        return status(context, relative(context, name, ddName)
                .start(number, KeyRelation.values()[relation]));
    }

    /**
     * 直前に読んだレコードの相対レコード番号 (要件 FR-101)。
     *
     * <p>順次読みでは<b>読んでみるまで番号が決まらない</b>。空きスロットを飛ばすためである。
     * {@code RELATIVE KEY} の項目へ返す値がこれである。
     */
    public static int relativeNumber(ProgramContext context, String name, String ddName) {
        return relative(context, name, ddName).currentNumber();
    }

    /** 相対編成として引く。編成は翻訳時に決まっているので、ここは必ず当たる。 */
    private static RelativeDataSet relative(ProgramContext context, String name, String ddName) {
        return (RelativeDataSet) context.file(name, ddName);
    }

    /**
     * 直前に読み書きしたレコードの長さ (要件 FR-106)。
     *
     * <p>可変長では<b>長さそのものがデータである</b>。{@code RECORD IS VARYING ... DEPENDING ON}
     * の項目へ入れる値がこれである。
     */
    public static int recordLength(ProgramContext context, String name, String ddName) {
        return context.file(name, ddName).lastLength();
    }

    /** {@code CLOSE} (要件 FR-102)。 */
    public static byte[] close(ProgramContext context, String name, String ddName) {
        return close(context, name, ddName, false);
    }

    /**
     * 閉じる (要件 FR-102)。
     *
     * @param lock {@code WITH LOCK} と書かれたか。書かれていれば、この実行単位では
     *             <b>二度と開けない</b>。次に開こうとすると状態コード 38 が立つ
     */
    public static byte[] close(ProgramContext context, String name, String ddName,
                               boolean lock) {
        String status = context.file(name, ddName).close();
        if (lock) {
            context.lockFile(name);
        }
        return status(context, status);
    }

    /**
     * 巻を送って閉じる — {@code CLOSE ... REEL} / {@code UNIT} (要件 FR-102)。
     *
     * <p>これは<b>ファイルを閉じない</b>。磁気テープなら、いまの巻を外して次の巻へ
     * 移る指示であり、ファイルそのものは開いたままである。ディスク上のデータセットには
     * 巻がないので、位置も内容も動かさず、巻の操作は行われなかったことを表す
     * {@code 07} を返す (85 規格 VII-38, 4.2.4(3)F)。
     *
     * <p>閉じないので、続けて {@code WRITE} も {@code READ} もできる。CCVS85 の
     * SQ123A / SQ124A はまさにそれを見ている — {@code CLOSE ... UNIT} のあとに
     * 書き足し、開き直さずに読み進める。
     */
    public static byte[] closeReel(ProgramContext context, String name, String ddName) {
        DataSet file = context.file(name, ddName);
        if (!file.isOpen()) {
            return status(context, FileStatus.NOT_OPEN);
        }
        return status(context, FileStatus.NON_REEL);
    }

    /**
     * 巻を戻さずに閉じる — {@code CLOSE ... WITH NO REWIND} (要件 FR-102)。
     *
     * <p>こちらは<b>閉じる</b>。違うのは、閉じたあとテープを巻き戻さないという点だけで
     * ある。ディスクには巻き戻しがないので閉じ方は変わらないが、巻の操作が行われな
     * かったことは {@code 07} で伝える (85 規格 VII-38, 4.2.4(3)F)。
     */
    public static byte[] closeNoRewind(ProgramContext context, String name, String ddName) {
        String status = context.file(name, ddName).close();
        return status(context, FileStatus.OK.equals(status) ? FileStatus.NON_REEL : status);
    }

    /**
     * 数字編集項目の中身から値を取り出す (要件 FR-060、de-editing)。
     *
     * <p>編集は「値 → 見せ方」の変換である。それを<b>逆にたどる</b>。通貨記号も
     * コンマも空白も値には関わらない。符号は {@code CR} / {@code DB} / {@code -} が
     * 表しており、そこだけを見る。
     *
     * <p>小数の桁数は<b>編集した項目の記述から翻訳時に決まる</b>。書かれた小数点の
     * 位置を数えないのは、浮動する記号や抑制で小数点が消えていることがあるためである。
     *
     * @param scale 編集した項目の小数の桁数
     */
    public static Decimal deEdit(Storage storage, int offset, int length, int scale,
                                 CodePage codePage) {
        String text = codePage.decode(read(storage, offset, length));
        boolean negative = text.contains("CR") || text.contains("DB") || text.indexOf('-') >= 0;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.isEmpty()) {
            // 空白だけなら 0 である。BLANK WHEN ZERO がそう書く
            return Decimal.zero(scale);
        }
        Decimal value = Decimal.parse(
                new java.math.BigDecimal(new java.math.BigInteger(digits.toString()), scale)
                        .toPlainString());
        return negative ? negate(value) : value;
    }

    /** 外から立てる切り替えを動かす (要件 FR-135)。 */
    public static void setSwitch(ProgramContext context, int index, boolean on) {
        context.switchState(index, on);
    }

    /** 外から立てる切り替えが立っているか (要件 FR-135)。 */
    public static boolean switchState(ProgramContext context, int index) {
        return context.switchState(index);
    }

    /**
     * 行き先を書かない {@code GO TO} を通った (要件 FR-063)。
     *
     * <p>投げる例外を<b>返す</b>のは、呼ぶ側が {@code athrow} で投げるためである。
     * こちらで投げると、生成した命令列のあとが到達不能だと検証器に伝わらない。
     */
    public static RuntimeException unalteredGoTo(String paragraph) {
        return new UnalteredGoToException(paragraph);
    }

    /**
     * べき乗 (要件 FR-047)。
     *
     * <p>指数が<b>整数</b>なら、答えは正確に出る。掛け算を重ねるだけだからである。
     * 負のべきは逆数になるので、そこで割り切れなければ近似が入る。
     *
     * <p>指数が整数でなければ、答えは<b>近似である</b>。対数を通るほかない。
     * 底が負ならその答えは実数にならないので、そこは誤りとして止める。
     */
    public static Decimal power(Decimal base, Decimal exponent) {
        java.math.BigDecimal value = base.toBigDecimal();
        java.math.BigDecimal times = exponent.toBigDecimal();
        if (times.stripTrailingZeros().scale() <= 0) {
            int whole = times.stripTrailingZeros().intValueExact();
            if (whole >= 0) {
                return decimalOf(value.pow(whole));
            }
            if (value.signum() == 0) {
                throw new ArithmeticException("zero cannot be raised to a negative power");
            }
            return decimalOf(java.math.BigDecimal.ONE.divide(value.pow(-whole), APPROXIMATE));
        }
        if (value.signum() < 0) {
            throw new ArithmeticException(
                    "a negative number cannot be raised to a fractional power: " + value);
        }
        if (value.signum() == 0) {
            return Decimal.zero(0);
        }
        double result = Math.pow(value.doubleValue(), times.doubleValue());
        if (!Double.isFinite(result)) {
            throw new ArithmeticException("the result of exponentiation is not a number");
        }
        return decimalOf(new java.math.BigDecimal(result).round(APPROXIMATE));
    }

    /** {@link java.math.BigDecimal} から {@link Decimal} を作る。 */
    private static Decimal decimalOf(java.math.BigDecimal value) {
        java.math.BigDecimal trimmed = value.stripTrailingZeros();
        return Decimal.parse((trimmed.scale() < 0 ? trimmed.setScale(0) : trimmed)
                .toPlainString());
    }

    /**
     * 近似が入る計算の桁数。
     *
     * <p>組み込み関数と同じ 15 桁である。同じ根から出る値が場所によって違う桁数に
     * なると、突き合わせられなくなる。
     */
    private static final java.math.MathContext APPROXIMATE =
            new java.math.MathContext(15, java.math.RoundingMode.HALF_UP);

    /**
     * ファイル状態コードをバイト列にする。
     *
     * <p>{@code FILE STATUS} の項目は<b>2 文字の英数字</b>である。数値ではない。
     * 拡張コードに数字でないものがあるためである。
     */
    private static byte[] status(ProgramContext context, String status) {
        return context.codePage().encode(status);
    }

    /**
     * ファイル状態コードが成功を表すか (要件 FR-103)。
     *
     * <p>先頭が {@code 0} なら成功か軽微な注意である。{@code 1} で始まれば
     * ファイルの終わりであり、それ以外は誤りである。
     */
    public static boolean fileSucceeded(byte[] status, CodePage codePage) {
        return FileStatus.succeeded(codePage.decode(status));
    }

    /** ファイルの終わりかどうか。{@code AT END} の分岐に使う。 */
    public static boolean fileAtEnd(byte[] status, CodePage codePage) {
        return FileStatus.AT_END.equals(codePage.decode(status));
    }

    /**
     * 鍵に関する誤りかどうか (要件 FR-103)。{@code INVALID KEY} の分岐に使う。
     *
     * <p>順編成の {@code AT END} にあたるものが、鍵で引く編成ではこれである。
     */
    public static boolean fileInvalidKey(byte[] status, CodePage codePage) {
        return FileStatus.invalidKey(codePage.decode(status));
    }

    /**
     * {@code FILE STATUS} を書いていないファイルで異常が起きたときの扱い (要件 FR-104)。
     *
     * <p>黙って続けると、<b>読めていないデータで処理が進む</b>。異常終了させる。
     *
     * <p>ただし文に受け止める句が書いてあれば、そちらへ分岐するのが正しい。
     * {@code AT END} を書いた {@code READ} でファイルの終わりに来るのは誤りではない。
     *
     * @param atEndHandled      文に {@code AT END} が書かれているか
     * @param invalidKeyHandled 文に {@code INVALID KEY} が書かれているか
     */
    public static void checkFile(byte[] status, CodePage codePage, String name,
                                 boolean atEndHandled, boolean invalidKeyHandled) {
        if (fileFailed(status, codePage, atEndHandled, invalidKeyHandled)) {
            throw new FileOperationException(name, codePage.decode(status));
        }
    }

    /**
     * 受け止め手のない異常かどうか (要件 FR-104, FR-105)。
     *
     * <p>{@code USE AFTER STANDARD ERROR PROCEDURE} を呼ぶかどうかの判定であり、
     * {@code FILE STATUS} を書いていないときに異常終了させるかどうかの判定でもある。
     * 文に受け止める句があれば、そちらへ分岐するのが正しい。
     */
    public static boolean fileFailed(byte[] status, CodePage codePage, boolean atEndHandled,
                                     boolean invalidKeyHandled) {
        String text = codePage.decode(status);
        if (FileStatus.succeeded(text)) {
            return false;
        }
        if (atEndHandled && FileStatus.AT_END.equals(text)) {
            return false;
        }
        return !(invalidKeyHandled && FileStatus.invalidKey(text));
    }

    /**
     * いまの開き方 (要件 FR-105)。
     *
     * <p>{@code USE ... ON INPUT} のように<b>開き方で指定した宣言節</b>が、
     * その入出力に効くかどうかを決めるために要る。
     *
     * @return {@link OpenMode} の並び順。開いていなければ {@code -1}
     */
    public static int fileMode(ProgramContext context, String name, String ddName) {
        OpenMode mode = context.file(name, ddName).mode();
        return mode == null ? -1 : mode.ordinal();
    }

    // ---- 整列と合併 ----

    /**
     * 整列作業ファイルを用意する (要件 FR-120)。
     *
     * <p>{@code SORT} のたびに作り直す。前の整列の中身が残っていてはならない。
     */
    public static void sortOpen(ProgramContext context, String work, SortKey[] keys) {
        context.sortWork(work, List.of(keys));
    }

    /** 照合順序を決めて用意する (要件 FR-054, FR-120)。 */
    public static void sortOpen(ProgramContext context, String work, SortKey[] keys,
                                CollatingSequence sequence) {
        context.sortWork(work, List.of(keys), sequence);
    }

    /** {@code RELEASE} (要件 FR-120)。レコードを 1 つ渡す。 */
    public static void release(ProgramContext context, String work, Storage storage, int offset,
                               int length) {
        context.sortWork(work).release(read(storage, offset, length));
    }

    /** 並べ替える (要件 FR-120)。安定であり、鍵が等しいレコードは入れた順のまま残る。 */
    public static void sortRecords(ProgramContext context, String work) {
        context.sortWork(work).sort();
    }

    /**
     * {@code RETURN} (要件 FR-120)。
     *
     * @return 返すものがなければ {@code false}。{@code AT END} の分岐に使う
     */
    public static boolean sortReturn(ProgramContext context, String work, Storage storage,
                                     int offset, int length) {
        byte[] record = new byte[length];
        if (!context.sortWork(work).next(record)) {
            return false;
        }
        storage.view(offset, length).setBytes(record);
        return true;
    }

    /**
     * {@code USING} のファイルを読み込む (要件 FR-120, FR-121)。
     *
     * <p>開いて全部読んで閉じるまでを行う。整列の入力にするファイルは、そのために
     * <b>プログラムが開いてはならない</b>ことになっている。開け閉めもこちらの仕事である。
     */
    public static void sortUsing(ProgramContext context, String work, String name, String ddName,
                                 int organization, int format, int recordLength) {
        DataSet file = context.file(name, ddName, Organization.values()[organization],
                RecordFormat.values()[format], recordLength);
        SortWork sort = context.sortWork(work);
        String status = file.open(OpenMode.INPUT, false);
        if (!FileStatus.succeeded(status)) {
            throw new FileOperationException(name, status);
        }
        byte[] record = new byte[recordLength];
        while (FileStatus.succeeded(file.read(record))) {
            sort.release(record);
        }
        file.close();
    }

    /** {@code GIVING} のファイルへ書き出す (要件 FR-120, FR-121)。 */
    public static void sortGiving(ProgramContext context, String work, String name, String ddName,
                                  int organization, int format, int recordLength) {
        DataSet file = context.file(name, ddName, Organization.values()[organization],
                RecordFormat.values()[format], recordLength);
        String status = file.open(OpenMode.OUTPUT, false);
        if (!FileStatus.succeeded(status)) {
            throw new FileOperationException(name, status);
        }
        byte[] record = new byte[recordLength];
        SortWork sort = context.sortWork(work);
        // GIVING に複数のファイルを書けば、どれにも同じレコードが全部入る (要件 FR-120)
        sort.rewind();
        while (sort.next(record)) {
            file.write(record);
        }
        file.close();
    }

    // ---- ACCEPT ----

    /**
     * 日付と時刻の特殊レジスタ (要件 FR-060、テスト時の固定は FR-204)。
     *
     * <p>返すのは<b>符号なし整数の表示形式</b>のバイト列である。受け取る項目が英数字なら
     * 数字がそのまま並び、数値なら {@link #asInteger} で整数として読まれる。
     */
    public static byte[] register(ProgramContext context, String form) {
        return SpecialRegisters.valueOf(SpecialRegisters.Form.valueOf(form), context.clock(),
                context.codePage());
    }

    /**
     * {@code ACCEPT} が端末から読む 1 行 (要件 FR-060)。
     *
     * <p>読んだ文字を実行時のコードページのバイト列へ直す。受け取る項目への詰め方は
     * 普通の転記と同じである。
     */
    public static byte[] acceptLine(ProgramContext context) {
        return context.codePage().encode(context.readLine());
    }

    /**
     * バイト列を符号なし整数として読む。
     *
     * <p>{@code ACCEPT} の送出側は<b>符号なし整数の表示形式</b>と決まっている。
     * 数値項目が受け取るときはこれを通す。
     */
    public static Decimal asInteger(byte[] bytes, CodePage codePage) {
        return ZonedDecimal.decode(bytes, 0, SignPosition.UNSIGNED, codePage, NumProcMode.NOPFD);
    }

    // ---- 副プログラムの呼び出し ----

    /**
     * {@code CALL} (要件 FR-080, FR-081)。
     *
     * <p>呼び先は名前ごとに 1 つだけ作って持ち続ける。COBOL では<b>副プログラムの
     * 作業場所は呼び出しをまたいで残る</b>ためである。
     *
     * <p>{@code GOBACK} と手続き部の終わりはここで受け止める。{@code STOP RUN} は
     * 受け止めない。<b>どこまで抜けるかが違う</b>のがこの 2 つの違いである。
     *
     * @param loader 呼ぶ側のクラスを読み込んだもの。生成クラスは同じところにある
     * @throws ProgramNotFoundException 呼び先が見つからない場合
     */
    public static void call(ProgramContext context, String name, ClassLoader loader,
                            DataView[] arguments) {
        ProgramContext.Loaded target = context.resolve(name, loader);
        context.enter(name, target.storage(), target.program().storageMap());
        try {
            target.program().run(target.storage(), context, arguments);
        } catch (ProgramReturn returned) {
            // 呼ばれた側が戻っただけである
        }
        // 異常終了で抜けたときは積まれたまま残す (要件 FR-142)
        context.leave();
    }

    /** 動的な {@code CALL}。呼び先の名前をデータ項目から読む。 */
    public static void call(ProgramContext context, byte[] name, ClassLoader loader,
                            DataView[] arguments) {
        call(context, context.codePage().decode(name).trim(), loader, arguments);
    }

    /**
     * {@code CANCEL} (要件 FR-083)。
     *
     * <p>読み込んだ副プログラムを忘れる。次に呼ばれたときは<b>作業場所が初期状態から</b>
     * 始まる。呼んでいないプログラムを取り消しても誤りではない。
     */
    public static void cancel(ProgramContext context, String name) {
        context.forget(name);
    }

    /** 動的な {@code CANCEL}。取り消す名前をデータ項目から読む。 */
    public static void cancel(ProgramContext context, byte[] name) {
        cancel(context, context.codePage().decode(name).trim());
    }

    /**
     * {@code BY CONTENT} の引数。
     *
     * <p>写しを渡す。呼ばれた側が書き換えても<b>呼ぶ側には届かない</b>。
     * これが {@code BY REFERENCE} との違いである。
     */
    public static DataView byContent(Storage storage, int offset, int length) {
        return Storage.copyOf(read(storage, offset, length)).whole();
    }

    /** 翻訳時に決まったバイト列を {@code BY CONTENT} で渡す。 */
    public static DataView byContent(byte[] bytes) {
        return Storage.copyOf(bytes).whole();
    }

    /** {@code BY REFERENCE} の引数。呼ぶ側の領域をそのまま渡す。 */
    public static DataView byReference(Storage storage, int offset, int length) {
        return storage.view(offset, length);
    }

    /** 引数の並びを作る。 */
    public static DataView[] arguments(DataView... views) {
        return views;
    }

    /**
     * {@code USING} の {@code index} 番目に渡された領域 (要件 FR-141)。
     *
     * <p>呼ぶ側が渡していなければ<b>そこで打ち切る</b>。ホストでは連絡節の項目は呼ぶ側の
     * 領域を指す仕掛け (BLL) だけを持ち、渡されていなければその仕掛けの中身が定まらない。
     * 運が悪ければ自分の持ち場の外を指し、{@code S0C4} で終わる。運がよければ何かが読めて
     * <b>誤った値のまま処理が進む</b>。
     *
     * <p>ここでは必ず {@code S0C4} で終わることにした。定まらない挙動を再現するより、
     * 誤りを誤りとして見せるほうがよいという判断である (要件 FR-205 の安全側)。
     *
     * @param item 診断に出す項目の名前
     */
    public static DataView linkage(DataView[] arguments, int index, String item) {
        if (arguments == null || index >= arguments.length || arguments[index] == null) {
            throw new Abend(AbendCode.S0C4,
                    "the caller did not pass an argument for " + item);
        }
        return arguments[index];
    }

    // ---- SSRANGE の検査 ----

    /**
     * 添字が {@code 1..occurs} に収まっているか検査する (要件 FR-024)。
     *
     * <p>値をそのまま返すのは、位置の計算の<b>途中に挟める</b>ようにするためである。
     * 検査のために計算を組み替えると、指定がないときの命令列まで変わってしまう。
     *
     * @param name 診断に出す項目の名前
     * @return 渡された添字
     */
    public static int checkSubscript(int value, int occurs, String name) {
        if (value < 1 || value > occurs) {
            throw new RangeCheckException("subscript " + value + " is outside 1.." + occurs
                    + " for " + name);
        }
        return value;
    }

    /**
     * 部分参照が項目の中に収まっているか検査する (要件 FR-026)。
     *
     * @param leftmost 開始位置 (1 起点)
     * @param length   長さ
     * @param size     項目の長さ
     * @param name     診断に出す項目の名前
     * @return 渡された開始位置
     */
    public static int checkRefMod(int leftmost, int length, int size, String name) {
        if (leftmost < 1 || leftmost > size) {
            throw new RangeCheckException("reference modification starts at " + leftmost
                    + " which is outside 1.." + size + " for " + name);
        }
        if (length < 1 || leftmost + length - 1 > size) {
            throw new RangeCheckException("reference modification of length " + length
                    + " at " + leftmost + " runs past the end of " + name
                    + " (" + size + " bytes)");
        }
        return leftmost;
    }

    // ---- 算術 ----

    public static Decimal add(Decimal left, Decimal right) {
        return left.add(right);
    }

    public static Decimal subtract(Decimal left, Decimal right) {
        return left.subtract(right);
    }

    public static Decimal multiply(Decimal left, Decimal right) {
        return left.multiply(right);
    }

    /**
     * 除算。
     *
     * <p>商の桁数は<b>受取項目の小数部に合わせる</b>。除算だけは結果の桁数が
     * 被演算子から決まらないため、受取側を見て決めるほかない。
     */
    public static Decimal divide(Decimal dividend, Decimal divisor, int scale,
                                 CobolRounding rounding) {
        return Arithmetic.divide(dividend, divisor, scale, rounding);
    }

    public static Decimal negate(Decimal value) {
        return value.negate();
    }

    /**
     * 中間結果を指定の小数桁へ切り捨てる (要件 FR-047)。
     *
     * <p>中間結果の総桁数が上限を超えたときにだけ呼ばれる。丸めるのは受取項目へ
     * 格納する最後の 1 回だけであり、途中は切り捨てる。
     */
    public static Decimal truncate(Decimal value, int scale) {
        return value.rescale(scale, CobolRounding.TRUNCATION);
    }

    /**
     * {@code DIVIDE ... REMAINDER} の剰余 (要件 FR-044)。
     *
     * <p>剰余は<b>切り捨てた商</b>から求める。{@code ROUNDED} を書いても、剰余の計算に
     * 使う商は丸めない。丸めた商から求めると、商と剰余を足し戻したときに元の値にならない。
     *
     * @param quotientScale 商を受け取る項目の小数桁。ここで商を切り捨てる
     */
    public static Decimal remainder(Decimal dividend, Decimal divisor, int quotientScale) {
        return dividend.remainder(divisor, quotientScale);
    }

    /** 除数が 0 かどうか。{@code ON SIZE ERROR} つきの除算で、割る前に見る。 */
    public static boolean isZero(Decimal value) {
        return value.isZero();
    }

    /**
     * {@code ON SIZE ERROR} つきの格納。
     *
     * <p>桁に収まらなければ<b>受取項目を変えず</b>に {@code true} を返す。
     * 指定がないときとの違いは、あふれたときに受取項目へ何が残るかである。
     */
    public static boolean storeChecked(Decimal value, NumericItem target, Storage storage,
                                       int offset, CobolRounding rounding) {
        return Arithmetic.storeChecked(target, storage.view(offset, target.byteLength()),
                value, rounding);
    }

    /** 算術文の結果を受取項目へ格納する。上位桁は黙って切り捨てられる。 */
    public static void store(Decimal value, NumericItem target, Storage storage, int offset,
                             CobolRounding rounding) {
        Arithmetic.store(target, storage.view(offset, target.byteLength()), value, rounding);
    }

    /**
     * 算術文の結果を数字編集項目へ格納する (要件 FR-041)。
     *
     * <p>{@code GIVING} と {@code COMPUTE} の受取側は数字編集項目でもよい。
     * 丸めるのは<b>編集の前</b>である。編集は桁を絵に当てはめるだけの処理であり、
     * どちら向きに丸めるかを知らないからである。
     */
    public static void storeEdited(Decimal value, Picture target, Storage storage, int offset,
                                   CobolRounding rounding, CodePage codePage) {
        Move.toNumericEdited(value.rescale(target.scale(), rounding), target,
                storage.view(offset, target.size()), codePage);
    }

    /**
     * {@code ON SIZE ERROR} つきの、数字編集項目への格納。
     *
     * <p>桁に収まらなければ<b>受取項目を変えず</b>に {@code true} を返す。
     * 収まるかどうかを見るのは絵の桁数であり、編集用の文字は数えない。
     */
    public static boolean storeEditedChecked(Decimal value, Picture target, Storage storage,
                                             int offset, CobolRounding rounding,
                                             CodePage codePage) {
        Decimal rounded = value.rescale(target.scale(), rounding);
        if (!rounded.fitsInDigits(target.digits(), target.scale())) {
            return true;
        }
        Move.toNumericEdited(rounded, target, storage.view(offset, target.size()), codePage);
        return false;
    }

    /**
     * 英数字項目を数値として読む。
     *
     * <p>参照実装は英数字項目から数値項目への転記で、送出側を<b>符号なしの整数</b>として
     * 扱う。ゾーン 10 進の復号をそのまま使う。
     */
    public static Decimal readAsInteger(Storage storage, int offset, int length,
                                        CodePage codePage) {
        return ZonedDecimal.decode(read(storage, offset, length), 0,
                SignPosition.UNSIGNED, codePage, NumProcMode.NOPFD);
    }
}
