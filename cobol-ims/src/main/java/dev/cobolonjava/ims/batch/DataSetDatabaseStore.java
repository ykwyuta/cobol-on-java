package dev.cobolonjava.ims.batch;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * データセットの置き場 (P-155)。RDB の置き場が構成されていなければこれを使う。
 *
 * <p>データベースは DBD の {@code DATASET DD1=} の DD (無ければ DBD 名の DD) に {@link DatabaseFile} の形で置く。
 * 同期点では、変わったデータベースのデータセットを丸ごと書き直す。
 */
final class DataSetDatabaseStore implements DatabaseStore {

    private final ProgramContext context;
    private final Map<String, DatabaseFile> files = new HashMap<>();

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
    }

    @Override
    public void close() {
    }
}
