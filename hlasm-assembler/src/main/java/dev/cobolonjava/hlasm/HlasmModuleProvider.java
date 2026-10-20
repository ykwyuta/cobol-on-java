package dev.cobolonjava.hlasm;

/**
 * 組み立て済みの機械語を持つ生成クラスの印。
 *
 * <p>{@code V(名前)} で呼ばれる HLASM の副プログラムは、呼ぶ側と<b>同じ番地空間</b>に載せなければ
 * ならない。引数表の番地も、呼ばれた側が {@code BASR 14,15} で戻る番地も、呼ぶ側の番地空間の中の
 * 番地だからである。{@code CobolProgram} の入口 ({@code run}) は新しい番地空間を作ってしまうので、
 * 載せる側はこの印を見て機械語そのものを取り出す。
 */
public interface HlasmModuleProvider {

    /** 組み立て済みの制御節。 */
    ObjectModule hlasmModule();
}
