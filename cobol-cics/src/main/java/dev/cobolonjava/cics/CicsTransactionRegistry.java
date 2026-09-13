package dev.cobolonjava.cics;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 外部TRANSIDを任意のクラス名へ変換させない、許可リスト型registry。 */
public final class CicsTransactionRegistry {

    private final Map<TransId, CicsTransactionDefinition> definitions;

    public CicsTransactionRegistry(Collection<CicsTransactionDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<TransId, CicsTransactionDefinition> indexed = new LinkedHashMap<>();
        for (CicsTransactionDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (indexed.putIfAbsent(definition.transId(), definition) != null) {
                throw new IllegalArgumentException("duplicate TRANSID: " + definition.transId().value());
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    public CicsTransactionDefinition resolve(String externalTransId) {
        TransId id = TransId.of(externalTransId);
        CicsTransactionDefinition definition = definitions.get(id);
        if (definition == null) {
            throw new UnknownTransactionException(id);
        }
        if (!definition.enabled()) {
            throw new DisabledTransactionException(id);
        }
        return definition;
    }

    public Map<TransId, CicsTransactionDefinition> definitions() {
        return definitions;
    }
}
