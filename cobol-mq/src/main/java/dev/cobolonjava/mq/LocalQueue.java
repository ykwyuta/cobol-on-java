package dev.cobolonjava.mq;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 局所待ち行列 1 つ。中身は待ち行列管理者の錠 ({@link MqQueueManager#lock}) の下でだけ触る。
 *
 * <p>見える電文と、作業単位の中にあって見えない電文 (同期点の下で置いたもの・取ったもの) を分けて数える。
 * 深さ (CURDEPTH) には両方が入る。公開文書の MQIA_CURRENT_Q_DEPTH の説明が「確約されていない電文も数える」と
 * しているからである。
 */
final class LocalQueue {

    /** 待ち行列に置いた電文と、置いた順の通し番号、有効期限 (無期限なら null)。 */
    record Entry(MqMessage message, long sequence, int priority, Instant expiresAt) {
    }

    final QueueDefinition definition;
    final List<Entry> visible = new ArrayList<>();
    /** 作業単位の中で置いた・取った電文の数。確約か取り消しで戻る。 */
    int uncommitted;
    int openInput;
    int openOutput;
    boolean exclusive;
    boolean getInhibited;
    boolean putInhibited;

    LocalQueue(QueueDefinition definition) {
        this.definition = definition;
        this.getInhibited = definition.getInhibited();
        this.putInhibited = definition.putInhibited();
    }

    String name() {
        return definition.name();
    }

    int depth() {
        return visible.size() + uncommitted;
    }

    /** 期限の切れた電文を落とす。公開文書のとおり、期限切れの電文は MQGET に渡らない。 */
    void expire(Instant now) {
        visible.removeIf(e -> e.expiresAt() != null && !now.isBefore(e.expiresAt()));
    }

    /**
     * 渡すべき電文: 優先度の高いもの、同じ優先度なら先に置いたもの (MSGDLVSQ(PRIORITY)、z/OS の既定)。
     * 突き合わせる MsgId / CorrelId は null なら問わない。
     */
    Entry select(byte[] messageId, byte[] correlationId) {
        Entry best = null;
        for (Entry e : visible) {
            if (messageId != null && !java.util.Arrays.equals(messageId, e.message().messageId())) {
                continue;
            }
            if (correlationId != null && !java.util.Arrays.equals(correlationId, e.message().correlationId())) {
                continue;
            }
            if (best == null || e.priority() > best.priority()
                    || (e.priority() == best.priority() && e.sequence() < best.sequence())) {
                best = e;
            }
        }
        return best;
    }

    void insert(Entry entry) {
        visible.add(entry);
    }
}
