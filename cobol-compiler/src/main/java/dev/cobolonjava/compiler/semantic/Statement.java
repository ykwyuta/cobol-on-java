package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.file.KeyRelation;
import dev.cobolonjava.runtime.file.OpenMode;
import java.util.List;

/**
 * 手続き部の文 1 個 (要件 FR-060)。
 *
 * <p>意味論そのものはランタイムが持つ (方針 ARC-7)。ここにあるのは
 * <b>何をどれに対して行うか</b>だけであり、実際の移送や算術は行わない。
 */
public sealed interface Statement {

    /** ソース上の位置。 */
    Origin origin();

    /** 専用translatorが解析した最小EXEC CICS command。 */
    record Cics(
            CicsOperation operation,
            String target,
            /** LINK / XCTL の PROGRAM(データ名)、ABEND の ABCODE(データ名)。静的な名前なら null で、target を使う。 */
            DataReference programData,
            DataReference commarea,
            int length,
            boolean suppressDefaultHandling,
            boolean rollback,
            boolean cancel,
            boolean noDump,
            boolean immediate,
            Origin origin) implements Statement {
    }

    /** EXEC CICS ASSIGNの1つのoption。受取域へ置く値の種類と長さ。 */
    record CicsAssign(CicsAssignOption option, DataReference target, Origin origin)
            implements Statement {
    }

    /**
     * EXEC CICS SEND MAP / SEND TEXT / SEND CONTROL (設計 79 §8)。
     *
     * @param flags  {@code CicsRuntimeOps.SEND_*} の bit
     * @param cursor 画面位置、{@code CURSOR_NONE}、または {@code CURSOR_SYMBOLIC}
     * @param from   SEND MAP の記号マップ、SEND TEXT の文字。書かなければ null
     */
    record CicsSend(
            CicsSendKind kind,
            String map,
            String mapset,
            DataReference from,
            int flags,
            int cursor,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    /**
     * EXEC SQL の 1 文 (要件 FR-150)。
     *
     * @param operation   EXECUTE のときの操作。COMMIT / ROLLBACK では null
     * @param statementId 診断と計画の識別に使う原文の位置
     * @param sqlca       結果を書き戻す SQLCA
     */
    record Sql(
            SqlKind kind,
            String statementId,
            dev.cobolonjava.db2.SqlOperation operation,
            String sql,
            String cursor,
            boolean withHold,
            List<SqlHost> inputs,
            List<SqlHost> outputs,
            DataReference sqlca,
            Origin origin) implements Statement {
    }

    enum SqlKind {
        EXECUTE,
        COMMIT,
        ROLLBACK
    }

    /**
     * host variable 1 個。形は翻訳時に決めた {@code Db2RuntimeOps} の shape である。
     *
     * @param indicator null 標識。無ければ null
     */
    record SqlHost(DataReference value, DataReference indicator, int kind, int digits, int scale,
                   int extra) {
    }

    /**
     * EXEC CICS GET / PUT CONTAINER (設計 79 §9)。
     *
     * @param nameData 名前がデータ名なら 16 byte の域、定数なら null で nameLiteral を使う
     * @param channelLiteral CHANNELも書かなければ channelData とともに null (現在のchannel)
     * @param lengthLiteral PUT の FLENGTH 定数。書かなければ -1
     */
    record CicsContainer(
            boolean put,
            String nameLiteral,
            DataReference nameData,
            String channelLiteral,
            DataReference channelData,
            DataReference area,
            DataReference lengthData,
            int lengthLiteral,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    /** EXEC CICS WRITE FILE (暫定判断 P-131)。LENGTH / KEYLENGTH は書かなければ -1。 */
    record CicsWriteFile(
            String fileLiteral,
            DataReference fileData,
            DataReference from,
            DataReference ridfld,
            int length,
            int keyLength,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    /** EXEC CICS INQUIRE / SET TERMINAL UCTRANST (暫定判断 P-130)。 */
    record CicsTerminalUctran(
            boolean set,
            String terminalLiteral,
            DataReference terminalData,
            DataReference uctranst,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    /** EXEC CICS ENQ / DEQ RESOURCE(域) LENGTH(n)。資源は域の先頭 n byte である (暫定判断 P-128)。 */
    record CicsEnqueue(
            boolean enqueue,
            DataReference resource,
            int length,
            boolean noSuspend,
            boolean taskScope,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    /** EXEC CICS BIF DEEDIT FIELD(x)。英数字の域から数字だけを残して右へ詰める。 */
    record CicsDeedit(DataReference field, Origin origin) implements Statement {
    }

    /** EXEC CICS RECEIVE MAP (設計 79 §8.4)。INTO は入力側の記号マップ。 */
    record CicsReceiveMap(
            String map,
            String mapset,
            DataReference into,
            /* ASIS: 端末が UCTRAN でも入力を大文字にしない */
            boolean asis,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    enum CicsSendKind {
        MAP,
        TEXT,
        CONTROL
    }

    /**
     * EXEC CICS DELAY (設計 79 §7)。書かなかった単位は null。
     *
     * @param interval {@code INTERVAL(hhmmss)}。FORの単位と同時には書けない
     */
    record CicsDelay(
            Operand hours,
            Operand minutes,
            Operand seconds,
            Operand millis,
            Operand interval,
            boolean suppressDefaultHandling,
            Origin origin) implements Statement {
    }

    /** EXEC CICS ASKTIME。受取域を省けばEIBの日時だけを更新する (設計 79 §6.1)。 */
    record CicsAskTime(DataReference abstime, Origin origin) implements Statement {
    }

    /**
     * EXEC CICS FORMATTIME の初期subset (設計 79 §6.2)。
     *
     * @param dateSeparator 日付の区切り文字。区切らなければ null
     * @param timeSeparator 時刻の区切り文字。区切らなければ null
     */
    record CicsFormatTime(
            DataReference abstime,
            CicsDateOrder dateOrder,
            DataReference date,
            String dateSeparator,
            DataReference time,
            String timeSeparator,
            Origin origin) implements Statement {
    }

    /** FORMATTIMEの日付の並び。 */
    enum CicsDateOrder {
        DDMMYYYY,
        YYYYMMDD,
        MMDDYYYY
    }

    /** ASSIGNで対応するoptionと、その受取域の長さ (設計 79 §5)。 */
    enum CicsAssignOption {
        ABCODE(4),
        APPLID(8),
        PROGRAM(8);

        private final int length;

        CicsAssignOption(int length) {
            this.length = length;
        }

        public int length() {
            return length;
        }
    }

    enum CicsOperation {
        LINK,
        XCTL,
        RETURN,
        SYNCPOINT,
        ABEND
    }

    /** EXEC CICS HANDLE/IGNORE CONDITIONによるprogram入口内のcondition処置。 */
    record CicsCondition(
            CicsConditionAction action,
            int responseCode,
            String target,
            Origin origin) implements Statement {
    }

    enum CicsConditionAction {
        /** target段落へ移る。targetがnullならCICS既定処置へ戻す。 */
        HANDLE,
        /** conditionを無視して次の文へ進む。 */
        IGNORE
    }

    /** EXEC CICS PUSH/POP HANDLEによるcondition処置一式の退避・復元。 */
    record CicsHandleStack(CicsHandleStackAction action, Origin origin) implements Statement {
    }

    enum CicsHandleStackAction {
        PUSH,
        POP
    }

    /** EXEC CICS HANDLE ABENDのCOBOL LABEL形式と有効状態操作。 */
    record CicsAbendHandler(
            CicsAbendHandlerAction action, String target, Origin origin) implements Statement {
    }

    enum CicsAbendHandlerAction {
        LABEL,
        CANCEL,
        RESET
    }

    /** EXEC CICS ASSIGN ABCODEによる現在のabend code取得。 */

    /**
     * {@code MOVE} 文。
     *
     * @param source        送り出す側
     * @param targets       受け取る側。複数書ける
     * @param corresponding {@code CORRESPONDING} 指定かどうか
     */
    record Move(Operand source, List<Target> targets, boolean corresponding, Origin origin)
            implements Statement {

        public Move {
            targets = List.copyOf(targets);
        }

        /**
         * 受け取り側 1 個と、そこへの転記の種類。
         *
         * <p>種類は分類の組み合わせから翻訳時に決まる。<b>呼ぶ先が決まっていなければ
         * コードは生成できない</b>ため、実行時まで残さない。
         */
        public record Target(DataReference reference, MoveRules.Kind kind) {
        }
    }

    /**
     * 算術文 ({@code ADD} {@code SUBTRACT} {@code MULTIPLY} {@code DIVIDE})。
     *
     * <p>4 つの文はどれも「被演算子を左から畳んだ値を、受取項目へ入れるか、
     * 受取項目に対して演算する」形に落ちる。文ごとの違いは<b>畳み方と、
     * 受取項目を巻き込むかどうか</b>だけである。
     *
     * <pre>
     * ADD A B TO C          C = C + (A + B)      fold=ADD,      accumulate=ADD
     * ADD A B GIVING C      C = A + B            fold=ADD,      accumulate=null
     * SUBTRACT A B FROM C   C = C - (A + B)      fold=ADD,      accumulate=SUBTRACT
     * SUBTRACT A FROM B GIVING C
     *                       C = B - A            fold=SUBTRACT, accumulate=null
     * MULTIPLY A BY B       B = B * A            fold=MULTIPLY, accumulate=MULTIPLY
     * DIVIDE A INTO B       B = B / A            fold=DIVIDE,   accumulate=DIVIDE
     * DIVIDE A BY B GIVING C
     *                       C = A / B            fold=DIVIDE,   accumulate=null
     * </pre>
     *
     * @param fold       被演算子を左から畳む演算
     * @param operands   畳む順に並べた被演算子
     * @param accumulate 受取項目を巻き込む演算。{@code GIVING} の形では {@code null}
     * @param targets    受取項目
     */
    record Arithmetic(Operator fold, List<Operand> operands, Operator accumulate,
                      List<Target> targets, SizeError sizeError, Origin origin)
            implements Statement {

        public Arithmetic {
            operands = List.copyOf(operands);
            targets = List.copyOf(targets);
        }

        /**
         * {@code ON SIZE ERROR} の指定があるかどうか。
         *
         * <p>指定の有無で<b>桁があふれたときに受取項目に何が残るか</b>が変わる。
         * 指定があれば受取項目は変わらず、なければ上位桁を切り捨てた値が入る。
         */
        public boolean isChecked() {
            return sizeError != null;
        }

        /**
         * {@code ON SIZE ERROR} と {@code NOT ON SIZE ERROR} の文。
         *
         * @param onError   桁あふれか 0 除算が起きたときの文
         * @param otherwise どちらも起きなかったときの文
         */
        public record SizeError(List<Statement> onError, List<Statement> otherwise) {

            public SizeError {
                onError = List.copyOf(onError);
                otherwise = List.copyOf(otherwise);
            }
        }

        /** 演算の種類。 */
        public enum Operator {
            ADD, SUBTRACT, MULTIPLY, DIVIDE
        }

        /**
         * 受取項目 1 個。
         *
         * @param rounded {@code ROUNDED} 指定。ないときは切り捨てになる
         */
        public record Target(DataReference reference, boolean rounded) {
        }
    }

    /**
     * 1 つの {@code ON SIZE ERROR} を分け合う算術文の集まり。
     *
     * <p>{@code ADD CORRESPONDING} は名前の合う組の数だけ加算を行うが、
     * <b>条件文を通るのは全部を計算し終えたあと 1 度だけ</b>である。組ごとに独立した
     * 算術文へ展開すると、あふれた組の数だけ条件文を通ってしまう。
     *
     * <p>そのため、条件を分け合うという関係だけをここで表す。中身は普通の
     * {@link Arithmetic} であり、それぞれの {@code sizeError} は {@code null} である。
     *
     * @param operations 順に行う算術文
     * @param sizeError  全体で 1 つの条件。指定がなければ {@code null}
     */
    record ArithmeticGroup(List<Arithmetic> operations, Arithmetic.SizeError sizeError,
                           Origin origin) implements Statement {

        public ArithmeticGroup {
            operations = List.copyOf(operations);
        }
    }

    /**
     * {@code DIVIDE ... REMAINDER}。
     *
     * <p>ほかの算術文と違い、<b>1 回の計算から 2 つの値が出る</b>。商と剰余は
     * 別々の受取項目へ入り、それぞれに {@code ROUNDED} を書ける。
     *
     * <p>剰余は<b>切り捨てた商</b>から求める。商に {@code ROUNDED} を書いても、
     * 剰余の計算に使う商は丸めない。
     *
     * @param dividend  割られる側
     * @param divisor   割る側
     * @param quotient  商の受取項目
     * @param remainder 剰余の受取項目
     */
    record DivideRemainder(Operand dividend, Operand divisor, Arithmetic.Target quotient,
                           Arithmetic.Target remainder, Arithmetic.SizeError sizeError,
                           Origin origin) implements Statement {

        /** {@code ON SIZE ERROR} の指定があるかどうか。 */
        public boolean isChecked() {
            return sizeError != null;
        }
    }

    /**
     * {@code COMPUTE} 文。
     *
     * <p>ほかの算術文との違いは<b>式を取る</b>ことだけである。受取項目と
     * {@code ON SIZE ERROR} は {@link Arithmetic} と同じものを使う。
     *
     * <p>中間結果の桁数は翻訳時に決まる ({@link IntermediateDigits})。式の木そのものには
     * 桁数を持たせない。桁数は<b>文全体</b>から決まるため、節ごとに持つと決めた場所が分かれる。
     */
    record Compute(Expression value, List<Arithmetic.Target> targets,
                   Arithmetic.SizeError sizeError, Origin origin) implements Statement {

        public Compute {
            targets = List.copyOf(targets);
        }

        /** {@code ON SIZE ERROR} の指定があるかどうか。 */
        public boolean isChecked() {
            return sizeError != null;
        }
    }

    /**
     * {@code IF} 文。
     *
     * @param onTrue  条件が成り立つときの文。{@code NEXT SENTENCE} は空の並びになる
     * @param onFalse {@code ELSE} の文。なければ空
     */
    record If(Condition condition, List<Statement> onTrue, List<Statement> onFalse, Origin origin)
            implements Statement {

        public If {
            onTrue = List.copyOf(onTrue);
            onFalse = List.copyOf(onFalse);
        }
    }

    /**
     * {@code DISPLAY} 文。
     *
     * @param operands  並べて出す被演算子
     * @param advancing 行を改めるかどうか。{@code WITH NO ADVANCING} では改めない
     * @param upon      {@code UPON} で指定した行き先。指定がなければ標準出力
     */
    record Display(List<Operand> operands, boolean advancing, SpecialNames.FunctionName upon,
                   Origin origin) implements Statement {

        public Display {
            operands = List.copyOf(operands);
        }
    }

    /**
     * {@code INSPECT} 文。
     *
     * <p>句は<b>書かれた順のまま</b>持つ。単一の走査で位置ごとに順に試されるため、
     * 並べ替えると結果が変わる。
     *
     * @param target     検査する項目
     * @param clauses    数える句と置き換える句。書かれた順に並ぶ
     * @param converting {@code CONVERTING} の指定。なければ {@code null}
     */
    record Inspect(DataReference target, List<InspectClause> clauses, Converting converting,
                   Origin origin) implements Statement {

        public Inspect {
            clauses = List.copyOf(clauses);
        }

        /**
         * 句 1 個。
         *
         * @param kind    種別
         * @param pattern 照合する並び。{@code CHARACTERS} では {@code null}
         * @param to      置き換える並び。数えるだけの句では {@code null}
         * @param counter 数を足し込む項目。置き換えるだけの句では {@code null}
         * @param region  検査する範囲
         */
        public record InspectClause(Kind kind, Operand pattern, Operand to,
                                    DataReference counter, RegionSpec region) {
        }

        /** 句の種別。ランタイムの {@code InspectScan.Kind} に対応する。 */
        public enum Kind {
            CHARACTERS, ALL, LEADING, FIRST
        }

        /**
         * {@code BEFORE INITIAL} / {@code AFTER INITIAL} が定める範囲。
         *
         * @param after  この並びの直後から。指定がなければ {@code null}
         * @param before この並びの直前まで。指定がなければ {@code null}
         */
        public record RegionSpec(Operand after, Operand before) {
        }

        /** {@code CONVERTING 並び TO 並び}。 */
        public record Converting(Operand from, Operand to, RegionSpec region) {
        }
    }

    /**
     * {@code STRING} 文。
     *
     * <p>送出項目をつなげて 1 つの受取項目へ書く。<b>受取項目の残りは埋めない</b> —
     * 書いた分だけが変わる。{@code MOVE} が残りを空白で埋めるのとは違う。
     *
     * @param pointer  {@code WITH POINTER} の項目。指定がなければ {@code null}
     * @param overflow {@code ON OVERFLOW} の指定。なければ {@code null}
     */
    record StringStatement(List<StringSource> sources, DataReference target, DataReference pointer,
                           Overflow overflow, Origin origin) implements Statement {

        public StringStatement {
            sources = List.copyOf(sources);
        }

        /**
         * 送出する並び 1 組。
         *
         * @param delimiter {@code DELIMITED BY} の区切り。{@code SIZE} なら {@code null}
         */
        public record StringSource(List<Operand> values, Operand delimiter) {

            public StringSource {
                values = List.copyOf(values);
            }
        }
    }

    /**
     * {@code UNSTRING} 文。
     *
     * @param delimiters 区切りの並び。空なら受取項目の長さぶんを順に取る
     * @param pointer    {@code WITH POINTER} の項目。指定がなければ {@code null}
     * @param tallying   {@code TALLYING IN} の項目。指定がなければ {@code null}
     */
    record Unstring(DataReference source, List<UnstringDelimiter> delimiters,
                    List<UnstringTarget> targets, DataReference pointer, DataReference tallying,
                    Overflow overflow, Origin origin) implements Statement {

        public Unstring {
            delimiters = List.copyOf(delimiters);
            targets = List.copyOf(targets);
        }

        /**
         * 区切り 1 個。
         *
         * @param all {@code ALL} 指定。連続する区切りを 1 個として扱う
         */
        public record UnstringDelimiter(Operand value, boolean all) {
        }

        /**
         * 受取項目 1 個。
         *
         * @param delimiter {@code DELIMITER IN} の項目。指定がなければ {@code null}
         * @param count     {@code COUNT IN} の項目。指定がなければ {@code null}
         */
        public record UnstringTarget(DataReference field, DataReference delimiter,
                                     DataReference count) {
        }
    }

    /**
     * {@code ON OVERFLOW} と {@code NOT ON OVERFLOW} の文。
     *
     * @param onOverflow あふれたときの文
     * @param otherwise  あふれなかったときの文
     */
    record Overflow(List<Statement> onOverflow, List<Statement> otherwise) {

        public Overflow {
            onOverflow = List.copyOf(onOverflow);
            otherwise = List.copyOf(otherwise);
        }
    }

    /**
     * {@code STOP RUN} と {@code GOBACK}。
     *
     * <p>違いは<b>どこまで抜けるか</b>である。{@code STOP RUN} は実行そのものを終える。
     * {@code GOBACK} は呼んだ側へ 1 つ戻るだけであり、主プログラムでのみ実行の終わりになる。
     *
     * @param wholeRun {@code STOP RUN} かどうか。{@code GOBACK} では {@code false}
     */
    record Stop(boolean wholeRun, Origin origin) implements Statement {
    }

    /**
     * {@code EXIT PROGRAM} 文 (要件 FR-067)。
     *
     * <p>呼ばれた側から呼んだ側へ戻る。{@code GOBACK} と似ているが<b>同じではない</b>。
     * 主プログラムで書いた {@code EXIT PROGRAM} は<b>何もしない</b>という決まりがあり、
     * 次の文へ進む。{@code GOBACK} は主プログラムなら実行を終える。
     *
     * <p>したがって、どちらの意味になるかは<b>実行時にしか分からない</b>。同じ
     * プログラムが呼ばれることも主として動くこともあるからである。
     */
    record ExitProgram(Origin origin) implements Statement {
    }

    /**
     * 文の並び。1 つの文が<b>複数の文へ展開された</b>ときに使う。
     *
     * <p>{@code MOVE CORRESPONDING} は名前の合う組の数だけ {@code MOVE} になる。
     * 展開を意味解析で済ませておけば、コード生成は普通の {@code MOVE} を出すだけでよい。
     */
    /**
     * デバッグの節を動かす文のかたまり (要件 FR-193)。
     *
     * <p>ただの {@link Sequence} と分けてあるのは、<b>実行時の切り替えで丸ごと
     * 止められる</b>ようにするためである。切り替えを切ると 7 桁目の {@code D} の行は
     * 動いたまま、デバッグの節だけが動かなくなる。
     */
    record DebugEntry(List<Statement> body, Origin origin) implements Statement {

        public DebugEntry {
            body = List.copyOf(body);
        }
    }

    record Sequence(List<Statement> statements, Origin origin) implements Statement {

        public Sequence {
            statements = List.copyOf(statements);
        }
    }

    /** {@code CONTINUE} と {@code EXIT}。どちらも何もしない。 */
    record Continue(Origin origin) implements Statement {
    }

    /**
     * {@code CALL} 文 (要件 FR-080, FR-081)。
     *
     * @param target    呼び先。文字定数なら静的、データ項目なら実行時に名前が決まる
     * @param arguments 渡す引数。{@code USING} に並べた順
     * @param exception {@code ON EXCEPTION} の指定。なければ {@code null}
     */
    record Call(Operand target, List<Argument> arguments, Overflow exception, Origin origin)
            implements Statement {

        public Call {
            arguments = List.copyOf(arguments);
        }

        /**
         * 引数 1 個。
         *
         * @param byContent 写しを渡すかどうか。{@code false} なら領域そのものを渡す
         */
        public record Argument(Operand value, boolean byContent) {
        }
    }

    /** {@code CANCEL} 文。次に呼ばれたときの作業場所を初期状態へ戻す (要件 FR-083)。 */
    record Cancel(List<Operand> targets, Origin origin) implements Statement {

        public Cancel {
            targets = List.copyOf(targets);
        }
    }

    /**
     * {@code SEARCH} 文 (要件 FR-066)。
     *
     * <p>表を<b>いまの指標の位置から</b>順に見る。{@code SEARCH} は指標を初期化しない。
     * どこから見はじめるかは、直前の {@code SET} が決める。
     *
     * @param index   進める指標。表の {@code INDEXED BY} の 1 つ
     * @param varying 指標と一緒に進める項目。指定がなければ {@code null}
     * @param occurs  表の反復の回数。ここを超えたら {@code AT END} になる
     * @param atEnd   最後まで見つからなかったときの文
     * @param whens   条件と、成り立ったときの文。書かれた順に試す
     */
    record Search(DataReference index, DataReference varying, int occurs,
                  DataReference occursDepending,
                  List<Statement> atEnd, List<When> whens, Origin origin) implements Statement {

        public Search {
            atEnd = List.copyOf(atEnd);
            whens = List.copyOf(whens);
        }

        /** {@code WHEN} 1 個。 */
        public record When(Condition condition, List<Statement> statements) {

            public When {
                statements = List.copyOf(statements);
            }
        }
    }

    /**
     * {@code SEARCH ALL} 文。2 分探索である (要件 FR-066)。
     *
     * <p>逐次の {@code SEARCH} と違い、<b>指標は使う側が用意しなくてよい</b>。
     * 探索そのものが範囲を狭めながら指標を決める。当たれば指標はその位置を指し、
     * 当たらなければ値は決まらない。
     *
     * <p>書ける条件は<b>鍵と値の等号だけ</b>である。任意の条件を書けないのは、
     * 2 分探索が「大きいか小さいか」で半分を捨てる仕組みだからである。
     *
     * @param occursDepending {@code OCCURS ... DEPENDING ON} の項目。無ければ {@code null}
     * @param keys  鍵ごとの照合。表に書かれた順に並ぶ
     * @param whenStatements 当たったときの文
     */
    record SearchAll(DataReference index, int occurs, DataReference occursDepending,
                     List<KeyTest> keys,
                     List<Statement> atEnd, List<Statement> whenStatements, Origin origin)
            implements Statement {

        public SearchAll {
            keys = List.copyOf(keys);
            atEnd = List.copyOf(atEnd);
            whenStatements = List.copyOf(whenStatements);
        }

        /**
         * 鍵 1 個の照合。
         *
         * @param ascending 昇順かどうか。半分を捨てる向きが決まる
         * @param test      鍵と値の等号。3 方向の比較にはこの両辺を使う
         */
        public record KeyTest(boolean ascending, Condition.Relation test) {
        }
    }

    /**
     * {@code ACCEPT} 文 (要件 FR-060、テスト時の固定は FR-204)。
     *
     * <p>送出側は<b>符号なし整数の表示形式のバイト列</b>である。日付と時刻の特殊レジスタも、
     * 端末から読んだ 1 行も同じ形であり、受け取る項目への詰め方が分類で決まる。
     *
     * @param source   受け取る値の出どころ
     * @param register 特殊レジスタの形式。端末からの入力では {@code null}
     * @param from     {@code FROM} で指定した呼び名の機能名。指定がなければ {@code null}
     * @param kind     受け取る項目への詰め方
     */
    record Accept(DataReference target, Source source, String register, MoveRules.Kind kind,
                  Origin origin) implements Statement {

        /** 値の出どころ。 */
        public enum Source {
            /** 端末からの 1 行。 */
            CONSOLE,
            /** 日付と時刻の特殊レジスタ。 */
            REGISTER
        }
    }

    /**
     * {@code INITIALIZE} 文 (要件 FR-060)。
     *
     * <p>書き込むバイト列は翻訳時に決まるが、組み立てるのは<b>コード生成</b>である
     * ({@link InitializeImage})。実行時のコードページを知っているのはそちらだからである。
     * ここに持つのは書かれたとおりの指定だけである。
     *
     * @param target     初期化する項目
     * @param withFiller {@code FILLER} も初期化するか
     * @param replacing  {@code REPLACING} の指定
     */
    record Initialize(DataReference target, boolean withFiller,
                      List<InitializeImage.Replacing> replacing, Origin origin)
            implements Statement {

        public Initialize {
            replacing = List.copyOf(replacing);
        }
    }

    /**
     * {@code OPEN} 文 (要件 FR-102)。
     *
     * <p>1 つの {@code OPEN} で開き方の違う複数のファイルを開ける。
     * 開き方はファイルごとに決まるので、対にして持つ。
     */
    record Open(List<Opened> files, Origin origin) implements Statement {

        public Open {
            files = List.copyOf(files);
        }

        /**
         * 開くファイル 1 個と、その開き方。
         *
         * @param noRewind {@code WITH NO REWIND} と書かれたか。巻を持たない媒体では
         *                 巻き戻しようがないので、成功しても状態コードは {@code 07} になる
         * @param debug    ファイル名が見張られているときに、開いたあとで動かす文 (要件 FR-193)
         */
        public record Opened(FileDescription file, OpenMode mode, boolean noRewind,
                             List<Statement> debug) {

            public Opened {
                debug = List.copyOf(debug);
            }

            public Opened(FileDescription file, OpenMode mode) {
                this(file, mode, false, List.of());
            }
        }
    }

    /**
     * {@code CLOSE} 文 (要件 FR-102)。
     *
     * <p>巻の扱いは磁気テープの話だが、<b>翻訳の結果には効く</b>。{@code REEL} /
     * {@code UNIT} はファイルを閉じずに巻を送る指示であり、{@code NO REWIND} は
     * 閉じたあと巻き戻さない指示である。どちらも巻を持たない媒体では巻の操作が起きず、
     * 状態コード {@code 07} が立つ。{@code WITH LOCK} は錠を掛け、そのファイルを
     * <b>この実行単位では二度と開けなく</b>する。
     */
    record Close(List<Closed> files, Origin origin) implements Statement {

        public Close {
            files = List.copyOf(files);
        }

        /** 巻の扱い。 */
        public enum Volume {
            /** 巻を指す語がない。ふつうに閉じる。 */
            NONE,
            /** {@code REEL} / {@code UNIT}。<b>閉じない</b>。 */
            REEL,
            /** {@code WITH NO REWIND}。閉じるが巻き戻さない。 */
            NO_REWIND
        }

        /**
         * 閉じるファイル 1 個と、その閉じ方。
         *
         * @param lock   {@code WITH LOCK} と書かれたか
         * @param volume 巻を指す語が書かれたか
         */
        public record Closed(FileDescription file, boolean lock, Volume volume,
                             List<Statement> debug) {

            public Closed {
                debug = List.copyOf(debug);
            }

            public Closed(FileDescription file, boolean lock) {
                this(file, lock, Volume.NONE, List.of());
            }
        }
    }

    /**
     * {@code READ} 文 (要件 FR-102, FR-103)。
     *
     * <p>{@code INTO} は<b>読んだあとの転記</b>である。読めなかったときは転記も起きない。
     * したがってレコード領域への読み込みと転記は分けて持ち、成功したときだけ転記を出す。
     *
     * @param into     {@code INTO} の転記。指定がなければ {@code null}
     * @param atEnd    {@code AT END} の文。指定がなければ空
     * @param notAtEnd {@code NOT AT END} の文。指定がなければ空
     */
    record Read(FileDescription file, boolean next, int keyIndex, Move into,
                List<Statement> atEnd, List<Statement> notAtEnd, KeyCheck keyCheck,
                List<Statement> debug, Origin origin)
            implements Statement {

        public Read(FileDescription file, boolean next, int keyIndex, Move into,
                    List<Statement> atEnd, List<Statement> notAtEnd, KeyCheck keyCheck,
                    Origin origin) {
            this(file, next, keyIndex, into, atEnd, notAtEnd, keyCheck, List.of(), origin);
        }

        public Read {
            atEnd = List.copyOf(atEnd);
            notAtEnd = List.copyOf(notAtEnd);
            debug = List.copyOf(debug);
        }
    }

    /**
     * {@code INVALID KEY} と {@code NOT INVALID KEY} (要件 FR-103)。
     *
     * <p>鍵で引く編成での {@code AT END} にあたる。求めたレコードがなかった、
     * すでにあった、範囲の外だった — いずれも<b>鍵が使えなかった</b>ことである。
     */
    record KeyCheck(List<Statement> onInvalid, List<Statement> otherwise) {

        public KeyCheck {
            onInvalid = List.copyOf(onInvalid);
            otherwise = List.copyOf(otherwise);
        }
    }

    /**
     * {@code WRITE} 文 (要件 FR-102)。
     *
     * <p>書くのに指定するのは<b>レコード名</b>であってファイル名ではない。どのファイルへ
     * 書くのかは、そのレコードがどの {@code FD} の下にあるかで決まる。
     *
     * @param record 書き出すレコード記述
     * @param from   {@code FROM} の転記。指定がなければ {@code null}
     */
    record Write(FileDescription file, DataItem record, Move from, KeyCheck keyCheck,
                 Advancing advancing, PageCheck pageCheck, List<Statement> debug, Origin origin)
            implements Statement {

        public Write {
            debug = List.copyOf(debug);
        }

        public Write(FileDescription file, DataItem record, Move from, KeyCheck keyCheck,
                     Advancing advancing, PageCheck pageCheck, Origin origin) {
            this(file, record, from, keyCheck, advancing, pageCheck, List.of(), origin);
        }
    }

    /**
     * {@code AT END-OF-PAGE} と {@code NOT AT END-OF-PAGE} (要件 FR-113)。
     *
     * <p>頁の終わりに達したかどうかで分かれる。達したかを決めるのは
     * {@code LINAGE} が定める脚注の行であり、書いたあとの {@code LINAGE-COUNTER} を見る。
     */
    record PageCheck(List<Statement> atEnd, List<Statement> otherwise) {

        public PageCheck {
            atEnd = List.copyOf(atEnd);
            otherwise = List.copyOf(otherwise);
        }
    }

    /**
     * {@code WRITE} の行送り (要件 FR-102)。
     *
     * <p>印字するファイルは<b>行を送ってから書く</b>か、<b>書いてから送る</b>。
     * {@code AFTER ADVANCING 2 LINES} なら 1 行空けてから書く。送る量は書かれた数か、
     * 実行時に決まるデータ項目である。
     *
     * <p>{@code PAGE} は次の頁の先頭へ送る。
     *
     * @param lines 送る行数。書かれた数なら定数、項目なら {@code null}
     * @param count 送る行数を持つ項目。定数なら {@code null}
     * @param page 頁の先頭へ送るか
     * @param before 書いてから送るか。{@code false} なら送ってから書く
     */
    record Advancing(Integer lines, DataReference count, boolean page, boolean before) {

        /** 何行送るかが翻訳時に決まっているか。 */
        public boolean fixed() {
            return lines != null;
        }
    }

    /**
     * {@code REWRITE} 文 (要件 FR-102)。
     *
     * <p>書き換える相手は<b>直前に読んだレコード</b>である。文には書かれていない。
     * 場所を持っているのは開いているファイルのほうであり、そこが覚えている。
     *
     * @param record 書き出すレコード記述
     * @param from   {@code FROM} の転記。指定がなければ {@code null}
     */
    record Rewrite(FileDescription file, DataItem record, Move from, KeyCheck keyCheck,
                   List<Statement> debug, Origin origin) implements Statement {

        public Rewrite {
            debug = List.copyOf(debug);
        }

        public Rewrite(FileDescription file, DataItem record, Move from, KeyCheck keyCheck,
                       Origin origin) {
            this(file, record, from, keyCheck, List.of(), origin);
        }
    }

    /**
     * {@code DELETE} 文 (要件 FR-101, FR-102)。
     *
     * <p>消す相手は、順アクセスなら<b>直前に読んだレコード</b>、乱アクセスなら<b>鍵の指す
     * レコード</b>である。文に書くのはファイル名だけであり、どちらかはアクセス様式で決まる。
     */
    record Delete(FileDescription file, KeyCheck keyCheck, List<Statement> debug, Origin origin)
            implements Statement {

        public Delete {
            debug = List.copyOf(debug);
        }

        public Delete(FileDescription file, KeyCheck keyCheck, Origin origin) {
            this(file, keyCheck, List.of(), origin);
        }
    }

    /**
     * {@code START} 文 (要件 FR-101)。
     *
     * <p>レコードを<b>読まない</b>。指定した鍵との関係を満たす最初のレコードへ位置を合わせ、
     * そのあとの順次読み出しがそこから始まる。
     *
     * @param keyIndex どの索引を使うか。{@code 0} が主鍵、{@code 1} 以降が副鍵
     * @param key      比べる鍵の項目
     * @param relation {@code KEY IS} に書いた関係。省略時は等号
     */
    record Start(FileDescription file, int keyIndex, DataReference key, KeyRelation relation,
                 KeyCheck keyCheck, List<Statement> debug, Origin origin) implements Statement {

        public Start {
            debug = List.copyOf(debug);
        }

        public Start(FileDescription file, int keyIndex, DataReference key, KeyRelation relation,
                     KeyCheck keyCheck, Origin origin) {
            this(file, keyIndex, key, relation, keyCheck, List.of(), origin);
        }
    }

    /**
     * {@code SORT} 文と {@code MERGE} 文 (要件 FR-120, FR-121)。
     *
     * <p>やることは 3 つある。<b>溜めて、並べ替えて、配る</b>。入口はファイルか手続きのどちらか
     * であり、出口も同じである。{@code MERGE} は入口がファイルに限られるだけで、
     * 溜めてから並べ替えれば結果は合併と同じになる。
     *
     * @param work    {@code SD} で書いた整列作業ファイル
     * @param keys    鍵。書かれた順に効く
     * @param using   {@code USING} のファイル。入力手続きを書いていれば空
     * @param input   {@code INPUT PROCEDURE} の節。{@code USING} を書いていれば {@code null}
     * @param giving  {@code GIVING} のファイル。出力手続きを書いていれば空
     * @param output  {@code OUTPUT PROCEDURE} の節。{@code GIVING} を書いていれば {@code null}
     * @param merge   {@code MERGE} 文かどうか
     */
    /**
     * @param sequence 並べ替えに使う照合順序。既定 (コードページの並び) なら {@code null}
     */
    record Sort(FileDescription work, List<SortKeySpec> keys, List<FileDescription> using,
                Procedure input, List<FileDescription> giving, Procedure output, boolean merge,
                byte[] sequence, Origin origin) implements Statement {

        public Sort {
            keys = List.copyOf(keys);
            using = List.copyOf(using);
            giving = List.copyOf(giving);
        }

        /** 鍵 1 個。 */
        public record SortKeySpec(DataReference reference, boolean ascending) {
        }

        /** 入力手続きと出力手続き。段落の範囲である。 */
        public record Procedure(String from, String through) {
        }
    }

    /** {@code RELEASE} 文 (要件 FR-120)。整列作業ファイルへレコードを 1 つ渡す。 */
    record Release(FileDescription work, DataItem record, Move from, Origin origin)
            implements Statement {
    }

    /**
     * {@code RETURN} 文 (要件 FR-120)。整列作業ファイルからレコードを 1 つ受け取る。
     *
     * <p>{@code AT END} は<b>書かなければならない</b>。整列の出口はいつか尽きるのであり、
     * 尽きたときの行き先を書かずに済ませられない。
     */
    record Return(FileDescription work, Move into, List<Statement> atEnd,
                  List<Statement> notAtEnd, Origin origin) implements Statement {

        public Return {
            atEnd = List.copyOf(atEnd);
            notAtEnd = List.copyOf(notAtEnd);
        }
    }

    /**
     * {@code GO TO} 文。
     *
     * <p>{@code PERFORM} と違い<b>戻ってこない</b>。段落の途中から別の段落へ移り、
     * そのまま流れ続ける。
     */
    /**
     * {@code SET 呼び名 TO ON/OFF} (要件 FR-135)。
     *
     * <p>外から立てる切り替えを、プログラムからも動かせる。記憶域を持たないので、
     * 転記ではなく<b>実行時の入口が持つ状態</b>を書き換える。
     */
    record SetSwitch(int index, boolean on, Origin origin) implements Statement {
    }

    /**
     * {@code GO TO} 文 (要件 FR-063)。
     *
     * @param target 飛び先の段落。<b>{@code GO TO.} と書かれていれば {@code null}</b>
     *               であり、{@code ALTER} が書き込むまで通ってはならない場所を表す
     */
    record GoTo(String target, Origin origin) implements Statement {
    }

    /**
     * 1 つの文 (センテンス)。終止符で区切られたひとまとまりである。
     *
     * <p>ふつうは並べて出すだけだが、{@code NEXT SENTENCE} の飛び先を決めるのに
     * <b>どこで文が終わるか</b>が要る。区切りを IR に残しているのはそのためである。
     */
    record Sentence(List<Statement> body, Origin origin) implements Statement {
    }

    /**
     * {@code NEXT SENTENCE} (要件 FR-061)。
     *
     * <p><b>いまの文の残りを飛ばして</b>、次の文の先頭へ移る。{@code CONTINUE} とは違う。
     * {@code CONTINUE} は「何もしない」であり、囲んでいる {@code IF} の外側にある
     * 同じ文の続きは実行される。
     */
    record NextSentence(Origin origin) implements Statement {
    }

    /**
     * {@code ALTER} (要件 FR-063)。
     *
     * <p>{@code GO TO} だけを書いた段落の<b>飛び先を実行時に書き換える</b>。
     * 規格が書き換えられる段落を「{@code GO TO} だけを書いた段落」に限っているのは、
     * 行き先が 1 つでなければ書き換える先が定まらないためである。
     */
    record Alter(List<Change> changes, Origin origin) implements Statement {

        /** 書き換え 1 つ。{@code from} の段落が {@code to} へ飛ぶようになる。 */
        public record Change(String from, String to) {
        }
    }

    /**
     * {@code GO TO ... DEPENDING ON} (要件 FR-063)。
     *
     * <p>値が 1 なら 1 つ目、2 なら 2 つ目へ飛ぶ。<b>並びの外なら飛ばない</b>。
     * 誤りにはならず、次の文へ進む。規格がそう決めている。
     */
    record GoToDepending(List<String> targets, DataReference selector, Origin origin)
            implements Statement {
    }

    /**
     * {@code PERFORM} 文。
     *
     * <p>繰り返しの指定と、繰り返す中身の 2 つからなる。中身は<b>段落を呼ぶか、
     * その場に書いた文か</b>のどちらかで、両方ということはない。
     *
     * @param target    呼ぶ段落の名前。その場に書く形では {@code null}
     * @param through   {@code THRU} で指定した最後の段落。なければ {@code null}
     * @param times     {@code TIMES} の回数。指定がなければ {@code null}
     * @param until     {@code UNTIL} の条件。指定がなければ {@code null}
     * @param testAfter {@code WITH TEST AFTER} 指定。中身を 1 度実行してから条件を見る
     * @param varying   {@code VARYING} … {@code AFTER} …。外側から内側の順。指定がなければ空
     * @param body      その場に書いた文。段落を呼ぶ形では空
     */
    record Perform(String target, String through, Operand times, Condition until,
                   boolean testAfter, List<Varying> varying, List<Statement> body,
                   List<Statement> debug, Origin origin)
            implements Statement {

        public Perform(String target, String through, Operand times, Condition until,
                       boolean testAfter, List<Varying> varying, List<Statement> body,
                       Origin origin) {
            this(target, through, times, until, testAfter, varying, body, List.of(), origin);
        }

        public Perform {
            varying = List.copyOf(varying);
            body = List.copyOf(body);
            debug = List.copyOf(debug);
        }

        /** 段落を呼ぶ形かどうか。 */
        public boolean callsParagraph() {
            return target != null;
        }

        /**
         * {@code VARYING} 1 段分。
         *
         * <p>{@code AFTER} で並べた段は、外側が 1 進むたびに内側が {@code from} へ戻る。
         * そのため {@code from} は<b>繰り返しのたびに評価しなおす</b>。
         *
         * @param target 変える項目
         * @param from   初期値
         * @param by     1 回ごとに足す値
         * @param until  やめる条件
         */
        public record Varying(DataReference target, Operand from, Operand by, Condition until,
                              List<Statement> debug, List<Statement> debugTest) {

            public Varying {
                debug = List.copyOf(debug);
                debugTest = List.copyOf(debugTest);
            }

            public Varying(DataReference target, Operand from, Operand by, Condition until) {
                this(target, from, by, until, List.of(), List.of());
            }
        }
    }
}
