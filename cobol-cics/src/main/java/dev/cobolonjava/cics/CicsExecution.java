package dev.cobolonjava.cics;

import java.util.Objects;

/** 生成programがtaskのCicsGatewayへ到達するためのsession service。 */
public final class CicsExecution {

    private final CicsTaskContext task;
    private CicsGateway gateway;

    public CicsExecution(CicsTaskContext task) {
        this.task = Objects.requireNonNull(task, "task");
    }

    public synchronized void bind(CicsGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        if (this.gateway != null) {
            throw new CicsTaskStateException("CICS execution is already bound");
        }
        this.gateway = gateway;
    }

    public CicsCommandOutcome execute(CicsCommand command) {
        CicsGateway current;
        synchronized (this) {
            current = gateway;
        }
        if (current == null) {
            throw new CicsTaskStateException("CICS execution is not bound to a gateway");
        }
        return Objects.requireNonNull(current.execute(command, task), "CICS command outcome");
    }
}
