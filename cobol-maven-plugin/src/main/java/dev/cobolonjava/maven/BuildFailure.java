package dev.cobolonjava.maven;

/** ビルドを止める誤り。診断は {@link Report} へ出し終えてから投げる。 */
final class BuildFailure extends Exception {

    private static final long serialVersionUID = 1L;

    BuildFailure(String message) {
        super(message);
    }
}
