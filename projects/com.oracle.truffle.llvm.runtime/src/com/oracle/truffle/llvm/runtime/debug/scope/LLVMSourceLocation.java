/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.debug.scope;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public abstract class LLVMSourceLocation {

    public abstract static class LazySourceSection {
        public abstract SourceSection get();

        public abstract Path getPath();

        public abstract int getLine();

        public abstract int getColumn();
    }

    private static final int DEFAULT_SCOPE_CAPACITY = 2;

    private static final SourceSection UNAVAILABLE_SECTION;

    static {
        final Source source = Source.newBuilder("Source unavailable!").name("<unavailable>").mimeType("text/plain").build();
        UNAVAILABLE_SECTION = source.createUnavailableSection();
    }

    private static final List<LLVMSourceSymbol> NO_SYMBOLS = Collections.emptyList();

    public enum Kind {
        TYPE,
        LINE,
        MODULE,
        BLOCK,
        FUNCTION,
        NAMESPACE,
        COMPILEUNIT,
        FILE,
        GLOBAL,
        LOCAL,
        UNKNOWN;
    }

    private final LLVMSourceLocation parent;
    private final Kind kind;
    private final String name;

    private final LazySourceSection lazySourceSection;
    private SourceSection sourceSection;

    private LLVMSourceLocation(LLVMSourceLocation parent, Kind kind, String name, LazySourceSection lazySourceSection) {
        this.parent = parent;
        this.kind = kind;
        this.name = name;
        this.lazySourceSection = lazySourceSection;
    }

    private LLVMSourceLocation(LLVMSourceLocation parent, Kind kind, String name, SourceSection sourceSection) {
        assert sourceSection != null;
        this.parent = parent;
        this.kind = kind;
        this.name = name;
        this.lazySourceSection = null;
        this.sourceSection = sourceSection;
    }

    public SourceSection getSourceSection() {
        CompilerAsserts.neverPartOfCompilation();
        if (sourceSection == null) {
            sourceSection = lazySourceSection == null ? UNAVAILABLE_SECTION : lazySourceSection.get();
        }
        return sourceSection;
    }

    public String describeFile() {
        CompilerAsserts.neverPartOfCompilation();
        return lazySourceSection != null ? lazySourceSection.getPath().getFileName().toString() : sourceSection.getSource().getName();
    }

    private int getLine() {
        return lazySourceSection != null ? lazySourceSection.getLine() : sourceSection.getStartLine();
    }

    private int getColumn() {
        return lazySourceSection != null ? lazySourceSection.getColumn() : sourceSection.getStartColumn();
    }

    public String describeLocation() {
        CompilerAsserts.neverPartOfCompilation();
        String sourceName = getSourceSection().getSource().getName();
        StringBuilder sb = new StringBuilder(sourceName);
        if (getSourceSection().isAvailable()) {
            int line = getSourceSection().getStartLine();
            int col = getSourceSection().getStartColumn();
            if (line >= 0) {
                sb.append(':').append(line);
                if (col >= 0) {
                    sb.append(':').append(col);
                }
            }
        }
        return sb.toString();
    }

    public LLVMSourceLocation getParent() {
        return parent;
    }

    public Kind getKind() {
        return kind;
    }

    public void addSymbol(@SuppressWarnings("unused") LLVMSourceSymbol symbol) {
    }

    public boolean hasSymbols() {
        return false;
    }

    public List<LLVMSourceSymbol> getSymbols() {
        return NO_SYMBOLS;
    }

    public LLVMSourceLocation getCompileUnit() {
        if (kind == Kind.COMPILEUNIT) {
            return this;

        } else if (parent != null) {
            return parent.getCompileUnit();

        } else {
            return null;
        }
    }

    @TruffleBoundary
    public String getName() {
        switch (kind) {
            case NAMESPACE: {
                if (name != null) {
                    return "namespace " + name;
                } else {
                    return "namespace";
                }
            }

            case FILE: {
                return String.format("<%s>", describeFile());
            }

            case COMPILEUNIT:
                return "<static>";

            case MODULE:
                if (name != null) {
                    return "module " + name;
                } else {
                    return "<module>";
                }

            case FUNCTION: {
                if (name != null) {
                    return name;
                } else {
                    return "<function>";
                }
            }

            case BLOCK:
                return "<block>";

            case LINE:
                return String.format("<%s>", describeLocation());

            case TYPE: {
                if (name != null) {
                    return name;
                } else {
                    return "<type>";
                }
            }

            case GLOBAL:
            case LOCAL:
                if (name != null) {
                    return name;
                } else {
                    return "<symbol>";
                }

            default:
                return "<scope>";
        }
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o instanceof LLVMSourceLocation) {
            final LLVMSourceLocation that = (LLVMSourceLocation) o;

            if (hasSymbols() != that.hasSymbols()) {
                return false;
            }

            if (getKind() != that.getKind()) {
                return false;
            }

            if (!Objects.equals(describeFile(), that.describeFile())) {
                return false;
            }

            if (getLine() != that.getLine() || getColumn() != that.getColumn()) {
                return false;
            }

            return Objects.equals(getParent(), that.getParent());
        }

        return false;
    }

    @Override
    public int hashCode() {
        int result = getKind().hashCode();
        result = 31 * result + (getName() != null ? getName().hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return describeLocation();
    }

    private static class LineScope extends LLVMSourceLocation {

        LineScope(LLVMSourceLocation parent, Kind kind, String name, LazySourceSection lazySourceSection) {
            super(parent, kind, name, lazySourceSection);
        }

        LineScope(LLVMSourceLocation parent, Kind kind, String name, SourceSection sourceSection) {
            super(parent, kind, name, sourceSection);
        }
    }

    private static class DefaultScope extends LineScope {

        @CompilationFinal private List<LLVMSourceSymbol> symbols;

        DefaultScope(LLVMSourceLocation parent, Kind kind, String name, LazySourceSection lazySourceSection) {
            super(parent, kind, name, lazySourceSection);
            this.symbols = null;
        }

        DefaultScope(LLVMSourceLocation parent, Kind kind, String name, SourceSection sourceSection) {
            super(parent, kind, name, sourceSection);
            this.symbols = null;
        }

        @TruffleBoundary
        @Override
        public void addSymbol(LLVMSourceSymbol symbol) {
            CompilerAsserts.neverPartOfCompilation("Source-Scope may only grow when parsing!");
            if (symbols == null) {
                symbols = new ArrayList<>(DEFAULT_SCOPE_CAPACITY);
            }
            symbols.add(symbol);
        }

        @TruffleBoundary
        @Override
        public boolean hasSymbols() {
            return symbols != null && !symbols.isEmpty();
        }

        @Override
        public List<LLVMSourceSymbol> getSymbols() {
            return symbols != null ? symbols : NO_SYMBOLS;
        }
    }

    private static final class FunctionScope extends DefaultScope {

        private final LLVMSourceLocation compileUnit;

        FunctionScope(LLVMSourceLocation parent, Kind kind, String name, LazySourceSection lazySourceSection, LLVMSourceLocation compileUnit) {
            super(parent, kind, name, lazySourceSection);
            this.compileUnit = compileUnit;
        }

        @Override
        public String describeLocation() {
            return String.format("%s at %s", getName(), super.describeLocation());
        }

        @Override
        public LLVMSourceLocation getCompileUnit() {
            return compileUnit;
        }
    }

    public static LLVMSourceLocation create(LLVMSourceLocation parent, LLVMSourceLocation.Kind kind, String name, LazySourceSection lazySourceSection, LLVMSourceLocation compileUnit) {
        switch (kind) {
            case LINE:
            case GLOBAL:
            case LOCAL:
                return new LineScope(parent, kind, name, lazySourceSection);

            case FUNCTION:
                if (compileUnit != null) {
                    return new FunctionScope(parent, kind, name, lazySourceSection, compileUnit);
                } else {
                    return new DefaultScope(parent, kind, name, lazySourceSection);
                }

            default:
                return new DefaultScope(parent, kind, name, lazySourceSection);
        }
    }

    public static LLVMSourceLocation createBitcodeFunction(String name, SourceSection sourceSection) {
        return new DefaultScope(null, Kind.FUNCTION, name, sourceSection != null ? sourceSection : UNAVAILABLE_SECTION);
    }

    public static LLVMSourceLocation createUnknown(SourceSection sourceSection) {
        return new LineScope(null, Kind.UNKNOWN, "<unknown>", sourceSection != null ? sourceSection : UNAVAILABLE_SECTION);
    }
}
