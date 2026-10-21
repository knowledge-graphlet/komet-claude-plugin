/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.stamp.print;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Runs the person's installed {@code typst}: found on the process's {@code PATH}, else where
 * their login shell finds it (Komet started from the Dock inherits no shell profile, so
 * Homebrew's bin is not on its path), else in the usual install folders. The compile runs
 * with {@code --root} set to the folder the source lives in and reports typst's own output
 * verbatim when it fails.
 */
public final class TypstCompiler {

    private static final int TIMEOUT_SECONDS = 120;
    private static final List<Path> USUAL = List.of(
            Path.of("/opt/homebrew/bin/typst"), Path.of("/usr/local/bin/typst"),
            Path.of(System.getProperty("user.home"), ".cargo", "bin", "typst"));

    private TypstCompiler() { }

    /**
     * One compile.
     *
     * @param ok         whether typst exited 0 and the PDF exists
     * @param pdf        the PDF written, or the path it would have been written to
     * @param output     typst's stdout and stderr together
     * @param executable the typst that ran, or {@code null} when none was found
     */
    public record Result(boolean ok, Path pdf, String output, Path executable) {

        /** @return a one-line report for a status line */
        public String report() {
            if (executable == null) {
                return output;
            }
            return ok ? "Wrote " + pdf + (output.isBlank() ? "" : " — typst: " + oneLine(output))
                    : "typst compile failed: " + oneLine(output.isBlank() ? "no output" : output);
        }

        private static String oneLine(String text) {
            return text.strip().replaceAll("\\s*\\n\\s*", " ⏎ ");
        }
    }

    /**
     * Where typst is.
     *
     * @return the executable, or empty when neither the path, the login shell nor the usual folders have it
     */
    public static Optional<Path> locate() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, "typst");
                if (Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        Optional<Path> fromShell = fromLoginShell();
        if (fromShell.isPresent()) {
            return fromShell;
        }
        return USUAL.stream().filter(Files::isExecutable).findFirst();
    }

    /** What the person's login shell resolves {@code typst} to, if it resolves it. */
    private static Optional<Path> fromLoginShell() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(shell, "-l", "-c", "command -v typst").redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || output.isEmpty()) {
                return Optional.empty();
            }
            String last = output.lines().reduce((a, b) -> b).orElse("");
            Path candidate = Path.of(last.strip());
            return candidate.isAbsolute() && Files.isExecutable(candidate) ? Optional.of(candidate) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * The font families the installed typst can use, lower-cased, as {@code typst fonts} lists
     * them; read once. Typst warns on every family of a font list it does not know, so a writer
     * names only these.
     *
     * @return the families, or empty when typst is not installed or would not list them
     */
    public static Optional<Set<String>> installedFonts() {
        return Fonts.INSTALLED;
    }

    /** Lazily read font list. */
    private static final class Fonts {
        private static final Optional<Set<String>> INSTALLED = read();

        private static Optional<Set<String>> read() {
            Optional<Path> typst = locate();
            if (typst.isEmpty()) {
                return Optional.empty();
            }
            try {
                Process process = new ProcessBuilder(typst.get().toString(), "fonts").redirectErrorStream(true).start();
                process.getOutputStream().close();
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    return Optional.empty();
                }
                Set<String> families = new TreeSet<>();
                out.lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(l -> families.add(l.toLowerCase(Locale.ROOT)));
                return Optional.of(Set.copyOf(families));
            } catch (IOException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * Compiles a Typst source to PDF: {@code typst compile --root <root> <source> <pdf>}.
     *
     * @param root   the project root for absolute paths, the folder the source lives in
     * @param source the {@code .typ} file
     * @param pdf    the PDF to write
     * @return the result, with typst's output
     */
    public static Result compile(Path root, Path source, Path pdf) {
        return compile(root, source, pdf, null);
    }

    /**
     * As {@link #compile(Path, Path, Path)}, with a folder of fonts typst reads besides the
     * system's ({@code --font-path}).
     *
     * @param root     the project root
     * @param source   the source
     * @param pdf      the PDF to write
     * @param fontPath the font folder, or {@code null}
     * @return the outcome
     */
    public static Result compile(Path root, Path source, Path pdf, Path fontPath) {
        Optional<Path> typst = locate();
        if (typst.isEmpty()) {
            return new Result(false, pdf, "typst is not installed where this process, your login shell or the usual folders can find it; "
                    + "brew install typst, then try again.", null);
        }
        List<String> args = new ArrayList<>(List.of("compile", "--root", root.toString()));
        if (fontPath != null) {
            args.add("--font-path");
            args.add(fontPath.toString());
        }
        args.add(source.toString());
        args.add(pdf.toString());
        return run(typst.get(), root, args, pdf);
    }

    /**
     * Renders one page of a source to PNG, for checks: {@code typst compile --root <root> --format png --pages <page> <source> <png>}.
     *
     * @param root   the project root
     * @param source the {@code .typ} file
     * @param png    the PNG to write
     * @param page   the 1-based page
     * @return the result
     */
    public static Result renderPage(Path root, Path source, Path png, int page) {
        return renderPage(root, source, png, page, null);
    }

    /** As {@link #renderPage(Path, Path, Path, int)}, with a font folder ({@code --font-path}). */
    public static Result renderPage(Path root, Path source, Path png, int page, Path fontPath) {
        Optional<Path> typst = locate();
        if (typst.isEmpty()) {
            return new Result(false, png, "typst is not installed", null);
        }
        List<String> args = new ArrayList<>(List.of("compile", "--root", root.toString(), "--format", "png", "--pages", Integer.toString(page), "--ppi", "110"));
        if (fontPath != null) {
            args.add("--font-path");
            args.add(fontPath.toString());
        }
        args.add(source.toString());
        args.add(png.toString());
        return run(typst.get(), root, args, png);
    }

    /**
     * Asks a compiled source where its labelled elements are: {@code typst eval --in <source>
     * 'query(<label>).map(it => (page: …, y: …))'}, answering typst's JSON list of {@code page}
     * and {@code y} (the offset from the page's top edge), or the failure. ({@code typst query}
     * is deprecated in typst 0.15 and answers nothing for labels, so this evaluates instead.)
     *
     * @param root     the project root
     * @param source   the source
     * @param label    the label, without its angle brackets
     * @param fontPath the font folder, or {@code null}
     * @return the JSON, or empty with the output when the evaluation failed
     */
    public static Result locate(Path root, Path source, String label, Path fontPath) {
        Optional<Path> typst = locate();
        if (typst.isEmpty()) {
            return new Result(false, source, "typst is not installed, or not where Komet can find it", null);
        }
        List<String> args = new ArrayList<>(List.of("eval", "--root", root.toString(), "--in", source.toString(), "--format", "json"));
        if (fontPath != null) {
            args.add("--font-path");
            args.add(fontPath.toString());
        }
        args.add("query(<" + label + ">).map(it => (page: it.location().page(), y: it.location().position().y))");
        return run(typst.get(), root, args, source);
    }

    /**
     * Evaluates an expression against a compiled source — {@code typst eval --in}, the 0.15 way
     * to query the labelled elements of a document — and answers its JSON.
     *
     * @param root       the project root
     * @param source     the source
     * @param expression the Typst expression, typically a {@code query(...)} mapped to plain values
     * @param fontPath   the font folder, or {@code null}
     * @return the JSON in {@link Result#output()}, or the failure's output with {@code ok} false
     */
    public static Result evaluate(Path root, Path source, String expression, Path fontPath) {
        Optional<Path> typst = locate();
        if (typst.isEmpty()) {
            return new Result(false, source, "typst is not installed, or not where Komet can find it", null);
        }
        List<String> args = new ArrayList<>(List.of("eval", "--root", root.toString(), "--in", source.toString(), "--format", "json"));
        if (fontPath != null) {
            args.add("--font-path");
            args.add(fontPath.toString());
        }
        args.add(expression);
        return run(typst.get(), root, args, source);
    }

    private static Result run(Path typst, Path dir, List<String> args, Path output) {
        List<String> command = new ArrayList<>();
        command.add(typst.toString());
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, output, "typst timed out after " + TIMEOUT_SECONDS + " s", typst);
            }
            boolean ok = process.exitValue() == 0 && Files.isRegularFile(output);
            return new Result(ok, output, out, typst);
        } catch (IOException e) {
            return new Result(false, output, "typst could not run: " + e.getMessage(), typst);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, output, "interrupted", typst);
        }
    }
}
