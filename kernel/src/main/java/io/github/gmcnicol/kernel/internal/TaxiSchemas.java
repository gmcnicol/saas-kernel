package io.github.gmcnicol.kernel.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lang.taxi.Compiler;
import lang.taxi.CompilerConfig;
import lang.taxi.TaxiDocument;
import lang.taxi.sources.SourceCode;

final class TaxiSchemas {
    private static final String STANDARD = "META-INF/saas-kernel/standard.taxi";

    private TaxiSchemas() {}

    static TaxiDocument compile(List<String> applicationSources, Function<String, String> read) {
        var sources = new ArrayList<SourceCode>();
        sources.add(source(STANDARD, read.apply(STANDARD)));
        applicationSources.forEach(path -> sources.add(source(path, read.apply(path))));
        return new Compiler(sources, List.of(), new CompilerConfig()).compile();
    }

    private static SourceCode source(String path, String content) {
        return new SourceCode(path, content, Path.of(path), "taxi");
    }
}
