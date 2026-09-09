package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.JavaCallable;

/** JUnit パラメータとして受け取れる COBOL テスト操作。 */
public interface CobolTestContext {

    CobolProgramFixture program(String name);

    CobolProgramMock stubProgram(String name);

    CobolProgramMock stubProgram(String name, JavaCallable answer);

    CobolProgramMock expectProgram(String name);

    CobolSectionMock mockSection(String program, String section);

    CobolSectionMock spySection(String program, String section);

    String output();

    CodePage codePage();

    CobolSession session();
}
