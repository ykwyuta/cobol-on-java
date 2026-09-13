package dev.cobolonjava.cics;

@FunctionalInterface
public interface CicsTaskBoundaryFactory {

    CicsTaskBoundary open(CicsTaskContext task, CicsTransactionDefinition definition);
}
