package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.Objects;

/** 生成programがtaskのCicsGatewayへ到達するためのsession service。 */
public final class CicsExecution {

    private final CicsTaskContext task;
    private final int commareaLength;
    private CicsGateway gateway;
    private CicsEib eib;
    private CodePage eibCodePage;

    public CicsExecution(CicsTaskContext task, int commareaLength) {
        this.task = Objects.requireNonNull(task, "task");
        if (commareaLength < 0 || commareaLength > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "commareaLength must fit EIBCALEN: " + commareaLength);
        }
        this.commareaLength = commareaLength;
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

    /** 実行時code pageで初期化した、task内で一つのEIBを返す。 */
    public synchronized CicsEib eib(CodePage codePage) {
        Objects.requireNonNull(codePage, "codePage");
        if (eib == null) {
            eib = new CicsEib(task, commareaLength, codePage);
            eibCodePage = codePage;
        } else if (!eibCodePage.name().equals(codePage.name())) {
            throw new CicsTaskStateException("CICS EIB code page changed within a task");
        }
        return eib;
    }
}
