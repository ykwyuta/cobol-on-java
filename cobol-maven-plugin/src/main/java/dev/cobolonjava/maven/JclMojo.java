package dev.cobolonjava.maven;

import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * JCL と宣言的形式のジョブ記述を、実行時と同じ読み取りで検め、成果物へ載せる。
 *
 * <p>プログラムの翻訳 ({@code compile}) のあと、{@code process-classes} の段で動く。
 */
@Mojo(name = "jcl", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class JclMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File outputDirectory;

    /** ジョブ記述の置き場。{@code .jcl} は JCL、{@code .job} は宣言的形式。 */
    @Parameter(property = "cobol.jclDirectory", defaultValue = "${project.basedir}/src/main/jcl")
    private File jclDirectory;

    /** 目録手続きと JCL の {@code INCLUDE} メンバの置き場。 */
    @Parameter(property = "cobol.proclibDirectory",
            defaultValue = "${project.basedir}/src/main/proclib")
    private File proclibDirectory;

    @Parameter(property = "cobol.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("skipped");
            return;
        }
        SourceLayout standard = SourceLayout.standard(basedir.toPath());
        SourceLayout layout = new SourceLayout(standard.cobol(), standard.copybooks(),
                standard.bms(), standard.pli(), standard.pliIncludes(), standard.hlasm(),
                jclDirectory.toPath(), proclibDirectory.toPath());
        try {
            JobCheck.run(layout, outputDirectory.toPath(), MavenReport.of(getLog()));
        } catch (BuildFailure e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot check job descriptions", e);
        }
    }
}
