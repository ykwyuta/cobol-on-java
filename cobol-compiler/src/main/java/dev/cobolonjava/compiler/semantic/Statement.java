package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
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
     */
    record Display(List<Operand> operands, boolean advancing, Origin origin) implements Statement {

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

    /** {@code STOP RUN} と {@code GOBACK}。実行を終える。 */
    record Stop(Origin origin) implements Statement {
    }

    /**
     * 文の並び。1 つの文が<b>複数の文へ展開された</b>ときに使う。
     *
     * <p>{@code MOVE CORRESPONDING} は名前の合う組の数だけ {@code MOVE} になる。
     * 展開を意味解析で済ませておけば、コード生成は普通の {@code MOVE} を出すだけでよい。
     */
    record Sequence(List<Statement> statements, Origin origin) implements Statement {

        public Sequence {
            statements = List.copyOf(statements);
        }
    }

    /** {@code CONTINUE} と {@code EXIT}。どちらも何もしない。 */
    record Continue(Origin origin) implements Statement {
    }

    /**
     * {@code GO TO} 文。
     *
     * <p>{@code PERFORM} と違い<b>戻ってこない</b>。段落の途中から別の段落へ移り、
     * そのまま流れ続ける。
     */
    record GoTo(String target, Origin origin) implements Statement {
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
                   boolean testAfter, List<Varying> varying, List<Statement> body, Origin origin)
            implements Statement {

        public Perform {
            varying = List.copyOf(varying);
            body = List.copyOf(body);
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
        public record Varying(DataReference target, Operand from, Operand by, Condition until) {
        }
    }
}
