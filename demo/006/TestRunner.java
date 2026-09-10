package demo;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * JUnit 5 テスト実行用エントリポイント（Maven プラグインや外部 CLI に頼らず起動可能）。
 */
public class TestRunner {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println(" [JUnit 5] Running COBOL Unit Tests (LoanAppTest) ");
        System.out.println("==================================================");

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(LoanAppTest.class))
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);

        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.err));

        if (summary.getTotalFailureCount() > 0) {
            System.err.println("\n[ERROR] Tests failed: " + summary.getTotalFailureCount());
            System.exit(1);
        } else {
            System.out.println("\n[SUCCESS] All COBOL unit tests passed successfully!");
            System.exit(0);
        }
    }
}
