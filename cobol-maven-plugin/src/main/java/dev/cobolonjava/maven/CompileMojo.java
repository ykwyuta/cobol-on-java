package dev.cobolonjava.maven;

import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.ProcessStatement;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * COBOL と PL/I を翻訳し、BMS を検めて、クラスと配備カタログを {@code target/classes} へ出す。
 *
 * <p>{@code compile} の段で動く。Java の翻訳のあとになるが、Java 側は生成クラスを名前で
 * 引く ({@code CobolSession}) ので順は関わらない。
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class CompileMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File outputDirectory;

    /** COBOL の原文の置き場。拡張子 {@code .cbl} / {@code .cob} / {@code .cobol}。 */
    @Parameter(property = "cobol.sourceDirectory",
            defaultValue = "${project.basedir}/src/main/cobol")
    private File cobolSourceDirectory;

    /**
     * 写し句の置き場。<b>書いた順に探す</b>。省けば {@code src/main/copybook}。
     * BMS の置き場はこの並びの最後に足される。
     */
    @Parameter
    private List<File> copybookDirectories;

    /** BMS の mapset の置き場。 */
    @Parameter(property = "cobol.bmsDirectory", defaultValue = "${project.basedir}/src/main/bms")
    private File bmsDirectory;

    /** PL/I の原文の置き場。拡張子 {@code .pli} / {@code .pl1}。 */
    @Parameter(property = "cobol.pliSourceDirectory",
            defaultValue = "${project.basedir}/src/main/pli")
    private File pliSourceDirectory;

    /** {@code %INCLUDE} の置き場。<b>書いた順に探す</b>。省けば {@code src/main/pli-include}。 */
    @Parameter
    private List<File> pliIncludeDirectories;

    /** COBOL を自由形式で読む。既定は固定形式 (7〜72 桁)。 */
    @Parameter(property = "cobol.freeFormat", defaultValue = "false")
    private boolean freeFormat;

    /**
     * 翻訳時オプション。{@code CBL} 文と同じ綴りで書く ({@code SSRANGE,ARITH(EXTEND)})。
     * ソースの {@code CBL} / {@code PROCESS} のほうがあとに重なる。
     */
    @Parameter(property = "cobol.compilerOptions")
    private String compilerOptions;

    @Parameter(property = "cobol.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("skipped");
            return;
        }
        SourceLayout standard = SourceLayout.standard(basedir.toPath());
        SourceLayout layout = new SourceLayout(cobolSourceDirectory.toPath(),
                copybookDirectories == null ? standard.copybooks()
                        : copybookDirectories.stream().map(File::toPath).toList(),
                bmsDirectory.toPath(), pliSourceDirectory.toPath(),
                pliIncludeDirectories == null ? standard.pliIncludes()
                        : pliIncludeDirectories.stream().map(File::toPath).toList(),
                standard.jcl(), standard.proclib());
        CompilerOptions options = compilerOptions == null || compilerOptions.isBlank()
                ? CompilerOptions.NONE : ProcessStatement.parse(compilerOptions);
        try {
            ProgramBuild.run(layout, outputDirectory.toPath(), freeFormat, options,
                    MavenReport.of(getLog()));
        } catch (BuildFailure e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot build programs", e);
        }
    }
}
