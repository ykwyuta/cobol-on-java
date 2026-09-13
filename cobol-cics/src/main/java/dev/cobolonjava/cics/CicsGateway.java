package dev.cobolonjava.cics;

/** CICS資源実装を生成COBOLから分離する交換可能port。 */
@FunctionalInterface
public interface CicsGateway {

    CicsCommandOutcome execute(CicsCommand command, CicsTaskContext task);
}
