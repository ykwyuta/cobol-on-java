package dev.cobolonjava.compiler.source;

/**
 * ソースの形式が妥当でないことを表す。
 *
 * <p>プリプロセッサは<b>流れ作業</b>である。行を継ぎ、写し句を展開し、語へ切る。途中で
 * 読めないものに出会ったら、そこから先のトークン列は作れない。構文解析のように誤りから
 * 回復して先へ進むことができないので、例外で止める。
 *
 * <p>ただし<b>止まるのはプリプロセッサの中だけ</b>である。処理系の入口
 * ({@link dev.cobolonjava.compiler.parser.CobolParsing}) はこれを受け止めて診断へ変える。
 * 呼ぶ側は診断を求めているのだから、例外が表へ出てはならない (暫定判断 P-062)。
 *
 * <p>位置を持たせてあるのは、診断へ変えたときに<b>直す場所を指せる</b>ようにするため
 * である。
 */
public class SourceFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Origin origin;

    public SourceFormatException(String message) {
        this(null, message);
    }

    public SourceFormatException(Origin origin, String message) {
        super(origin == null ? message : origin + ": " + message);
        this.origin = origin;
    }

    /**
     * 読めなかった場所。
     *
     * @return 位置を特定できなければ {@code null}
     */
    public Origin origin() {
        return origin;
    }

    /** 位置を除いた内容。診断の文面はこちらである。 */
    public String detail() {
        String message = getMessage();
        String prefix = origin == null ? null : origin + ": ";
        return prefix != null && message.startsWith(prefix)
                ? message.substring(prefix.length())
                : message;
    }
}
