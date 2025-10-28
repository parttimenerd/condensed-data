package me.bechberger.util;

import java.nio.file.Path;

public class JavaUtil {

    public static Path getJavaBinary() {
        var javaHome = Path.of(System.getProperty("java.home"));
        return javaHome.resolve("bin").resolve("java");
    }

    public static Path getJavacBinary() {
        var javaHome = Path.of(System.getProperty("java.home"));
        return javaHome.resolve("bin").resolve("javac");
    }

    public static Path compileIntoParentFolder(Path sourceFile) {
        var parentFolder = sourceFile.getParent();
        var fileName = sourceFile.getFileName().toString();
        Process compileProcess;
        try {
            compileProcess =
                    new ProcessBuilder(
                                    getJavacBinary().toAbsolutePath().toString(),
                                    sourceFile.getFileName().toString())
                            .directory(parentFolder.toFile())
                            .inheritIO()
                            .start();
            int exitCode = compileProcess.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Compilation failed with exit code " + exitCode);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile Java source file: " + sourceFile, e);
        }
        var classFileName = fileName.replaceAll("\\.java$", ".class");
        return parentFolder.resolve(classFileName);
    }
}
