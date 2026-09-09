package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
import java.util.List;

/**
 * 報告書 1 個の記述 ({@code RD} とその下の報告集団、要件 FR-214)。
 *
 * <p>報告書作成機能は「どんな行を、紙のどこへ置くか」を宣言で書く仕組みである。
 * 手続きに書くのは {@code INITIATE} / {@code GENERATE} / {@code TERMINATE} の 3 つだけで、
 * 行送りも改頁も見出しの再掲も、宣言から<b>翻訳時に組み立てる</b>。
 *
 * <p>この処理系では、報告書を<b>普通の記述と普通の文へ落として</b>実現している
 * (要件 C-4 が言う「プリプロセッサ方式」を意味解析の段でやる)。専用の実行時機構を
 * 持たないので、転記も編集も行送りも、すでに確かめてある道をそのまま通る。
 *
 * @param name      報告書の名前
 * @param file      書き出す先の {@code FD} の名前
 * @param page      紙 1 枚の形
 * @param groups    報告集団
 * @param registers 数え札と作業用の項目
 */
public record ReportDescription(String name, String file, PageShape page,
                                List<ReportGroup> groups, Registers registers, Origin origin) {

    public ReportDescription {
        groups = List.copyOf(groups);
    }

    /** その種類の報告集団。無ければ {@code null}。 */
    public ReportGroup groupOfType(ReportGroup.Type type) {
        for (ReportGroup group : groups) {
            if (group.type() == type) {
                return group;
            }
        }
        return null;
    }

    public ReportGroup group(String groupName) {
        for (ReportGroup group : groups) {
            if (groupName.equals(group.name())) {
                return group;
            }
        }
        return null;
    }

    /**
     * 報告書 1 個が持つ数え札と作業用の置き場。
     *
     * <p>{@code LINE-COUNTER} と {@code PAGE-COUNTER} は規格が決めた特殊レジスタである。
     * 残りはこの処理系が置いたもので、名前に {@code $} を含むため書かれた名前とは
     * ぶつからない。
     *
     * @param lineCounter 頁の何行目まで書いたか
     * @param pageCounter 何枚目か
     * @param target      次に書く行が頁の何行目か
     * @param advance     次の {@code WRITE} で送る行数。負なら改頁してから送る
     * @param started     最初の {@code GENERATE} が済んだか
     * @param eject       次の {@code WRITE} の前に改頁するか
     */
    public record Registers(DataItem lineCounter, DataItem pageCounter, DataItem target,
                            DataItem advance, DataItem started, DataItem eject) {
    }

    /**
     * 紙 1 枚の形 (要件 FR-214)。
     *
     * <p>書かれなかった値は規格が決めた既定で埋める。{@code HEADING} が無ければ 1、
     * {@code FIRST DETAIL} が無ければ {@code HEADING} と同じ、{@code LAST DETAIL} が
     * 無ければ {@code FOOTING} (それも無ければ {@code PAGE LIMIT})、
     * {@code FOOTING} が無ければ {@code LAST DETAIL} (それも無ければ {@code PAGE LIMIT})。
     *
     * @param limit       1 枚に置ける行数
     * @param heading     見出しを置ける最初の行
     * @param firstDetail 本文を置ける最初の行
     * @param lastDetail  本文を置ける最後の行
     * @param footing     脚注を置ける最初の行
     */
    public record PageShape(int limit, int heading, int firstDetail, int lastDetail, int footing) {

        /** 書かれなかったところを既定で埋めた形。 */
        public static PageShape of(int limit, Integer heading, Integer firstDetail,
                                   Integer lastDetail, Integer footing) {
            int h = heading == null ? 1 : heading;
            int fd = firstDetail == null ? h : firstDetail;
            int ft = footing == null ? (lastDetail == null ? limit : lastDetail) : footing;
            int ld = lastDetail == null ? ft : lastDetail;
            return new PageShape(limit, h, fd, ld, ft);
        }
    }
}
