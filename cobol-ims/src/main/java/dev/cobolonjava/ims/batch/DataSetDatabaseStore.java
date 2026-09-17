package dev.cobolonjava.ims.batch;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.store.CheckpointStore;
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * データセットの置き場 (P-155)。RDB の置き場が構成されていなければこれを使う。
 *
 * <p>データベースは DBD の {@code DATASET DD1=} の DD (無ければ DBD 名の DD) に {@link DatabaseFile} の形で置く。
 * 同期点では、変わったデータベースのデータセットを丸ごと書き直す。
 *
 * <p>記号 CHKP が退避した域は、{@code //IMSCKPT} が割り当てられていればそのデータセットに {@link CheckpointFile}
 * の形で置く (P-164)。データベースと同じ同期点で書くので、再始動した域とデータベースの状態が揃う。割り当てが
 * 無ければ検査点を持てないので、記号 CHKP と XRST は断る。
 */
final class DataSetDatabaseStore implements DatabaseStore, CheckpointStore {

    /** 記号 CHKP が退避した域を置くデータセットの DD 名。 */
    static final String CHECKPOINT_DD = "IMSCKPT";

    private final ProgramContext context;
    private final Map<String, DatabaseFile> files = new HashMap<>();
    /** 次の確定で書く検査点。{@code PSB 名/検査点 ID} で引く。 */
    private final Map<String, List<byte[]>> pendingCheckpoints = new LinkedHashMap<>();
    private CheckpointFile checkpointFile;
    /** データセットから読んだ検査点。まだ読んでいなければ {@code null}。 */
    private Map<String, List<byte[]>> storedCheckpoints;

    DataSetDatabaseStore(ProgramContext context) {
        this.context = context;
    }

    @Override
    public HierarchicalDatabase open(DatabaseDefinition dbd) {
        String ddName = dbd.dataSetName() != null ? dbd.dataSetName() : dbd.name();
        if (!context.catalog().isAssigned(ddName)) {
            throw new ImsBatchException("DD " + ddName + " for database " + dbd.name() + " is not allocated");
        }
        DatabaseFile file = new DatabaseFile(context.catalog().resolve(ddName), ddName, context.codePage());
        files.put(dbd.name(), file);
        return file.read(dbd);
    }

    @Override
    public void commit(Collection<HierarchicalDatabase> databases) {
        for (HierarchicalDatabase database : databases) {
            if (database.changed()) {
                files.get(database.definition().name()).write(database);
            }
        }
        writeCheckpoints();
    }

    @Override
    public CheckpointStore checkpoints() {
        return context.catalog().isAssigned(CHECKPOINT_DD) ? this : null;
    }

    @Override
    public void record(String psb, String checkpointId, List<byte[]> areas) {
        List<byte[]> copy = new ArrayList<>();
        // 呼ぶ側の域はこのあとも書き換わる。確定まで持つので写しを取る
        areas.forEach(area -> copy.add(area.clone()));
        pendingCheckpoints.put(psb + "/" + checkpointId, copy);
    }

    @Override
    public List<byte[]> load(String psb, String checkpointId) {
        String key = psb + "/" + checkpointId;
        List<byte[]> pending = pendingCheckpoints.get(key);
        return pending != null ? pending : stored().get(key);
    }

    /** 同じ ID の検査点は、あとのもので置き換えて書き直す。 */
    private void writeCheckpoints() {
        if (pendingCheckpoints.isEmpty()) {
            return;
        }
        Map<String, List<byte[]>> all = stored();
        all.putAll(pendingCheckpoints);
        pendingCheckpoints.clear();
        file().write(all);
    }

    private Map<String, List<byte[]>> stored() {
        if (storedCheckpoints == null) {
            storedCheckpoints = file().read();
        }
        return storedCheckpoints;
    }

    private CheckpointFile file() {
        if (checkpointFile == null) {
            if (!context.catalog().isAssigned(CHECKPOINT_DD)) {
                throw new ImsBatchException("DD " + CHECKPOINT_DD + " must be allocated to keep checkpoints");
            }
            checkpointFile = new CheckpointFile(context.catalog().resolve(CHECKPOINT_DD), CHECKPOINT_DD,
                    context.codePage());
        }
        return checkpointFile;
    }

    @Override
    public void close() {
    }
}
