/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.hotspot;

import static org.graalvm.compiler.core.CompilationWrapper.ExceptionAction.Diagnose;
import static org.graalvm.compiler.core.CompilationWrapper.ExceptionAction.ExitVM;
import static org.graalvm.compiler.core.GraalCompilerOptions.CompilationBailoutAsFailure;
import static org.graalvm.compiler.core.GraalCompilerOptions.CompilationFailureAction;
import static org.graalvm.compiler.core.phases.HighTier.Options.Inline;
import static org.graalvm.compiler.java.BytecodeParserOptions.InlineDuringParsing;

import java.io.PrintStream;

import org.graalvm.collections.EconomicMap;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.CompilationPrinter;
import org.graalvm.compiler.core.CompilationWrapper;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugDumpScope;
import org.graalvm.compiler.debug.TimerKey;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.printer.GraalDebugHandlersFactory;

import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotCompilationRequest;
import jdk.vm.ci.hotspot.HotSpotCompilationRequestResult;
import jdk.vm.ci.hotspot.HotSpotInstalledCode;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotNmethod;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCICompiler;

public class CompilationTask {

    private final HotSpotJVMCIRuntime jvmciRuntime;

    private final HotSpotGraalCompiler compiler;
    private final HotSpotCompilationIdentifier compilationId;

    private HotSpotInstalledCode installedCode;

    /**
     * Specifies whether the compilation result is installed as the
     * {@linkplain HotSpotNmethod#isDefault() default} nmethod for the compiled method.
     */
    private final boolean installAsDefault;

    private final boolean useProfilingInfo;
    private final boolean shouldRetainLocalVariables;

    final class HotSpotCompilationWrapper extends CompilationWrapper<HotSpotCompilationRequestResult> {
        CompilationResult result;

        HotSpotCompilationWrapper() {
            super(compiler.getGraalRuntime().getOutputDirectory(), compiler.getGraalRuntime().getCompilationProblemsPerAction());
        }

        @Override
        protected DebugContext createRetryDebugContext(OptionValues retryOptions, PrintStream logStream) {
            SnippetReflectionProvider snippetReflection = compiler.getGraalRuntime().getHostProviders().getSnippetReflection();
            return DebugContext.create(retryOptions, logStream, new GraalDebugHandlersFactory(snippetReflection));
        }

        @Override
        public String toString() {
            return getMethod().format("%H.%n(%p)");
        }

        @Override
        protected HotSpotCompilationRequestResult handleException(Throwable t) {
            if (t instanceof BailoutException) {
                BailoutException bailout = (BailoutException) t;
                /*
                 * Handling of permanent bailouts: Permanent bailouts that can happen for example
                 * due to unsupported unstructured control flow in the bytecodes of a method must
                 * not be retried. Hotspot compile broker will ensure that no recompilation at the
                 * given tier will happen if retry is false.
                 */
                return HotSpotCompilationRequestResult.failure(bailout.getMessage(), !bailout.isPermanent());
            }

            /*
             * Treat random exceptions from the compiler as indicating a problem compiling this
             * method. Report the result of toString instead of getMessage to ensure that the
             * exception type is included in the output in case there's no detail mesage.
             */
            return HotSpotCompilationRequestResult.failure(t.toString(), false);
        }

        @Override
        protected ExceptionAction lookupAction(OptionValues values, Throwable cause) {
            if (cause instanceof BailoutException) {
                BailoutException bailout = (BailoutException) cause;
                if (bailout.isPermanent()) {
                    // Respect current action if it has been explicitly set.
                    if (!CompilationBailoutAsFailure.hasBeenSet(values)) {
                        // Get more info for permanent bailouts during bootstrap.
                        if (compiler.getGraalRuntime().isBootstrapping()) {
                            return Diagnose;
                        }

                    }
                }
                if (!CompilationBailoutAsFailure.getValue(values)) {
                    return super.lookupAction(values, cause);
                }
            }

            // Respect current action if it has been explicitly set.
            if (!CompilationFailureAction.hasBeenSet(values)) {
                // Automatically exit on failure during bootstrap.
                if (compiler.getGraalRuntime().isBootstrapping()) {
                    return ExitVM;
                }
            }
            return super.lookupAction(values, cause);
        }

        @SuppressWarnings("try")
        @Override
        protected HotSpotCompilationRequestResult performCompilation(DebugContext debug) {
            HotSpotResolvedJavaMethod method = getMethod();
            int entryBCI = getEntryBCI();
            final boolean isOSR = entryBCI != JVMCICompiler.INVOCATION_ENTRY_BCI;
            CompilationStatistics stats = CompilationStatistics.create(debug.getOptions(), method, isOSR);

            final CompilationPrinter printer = CompilationPrinter.begin(debug.getOptions(), compilationId, method, entryBCI);

            try (DebugContext.Scope s = debug.scope("Compiling", new DebugDumpScope(getIdString(), true))) {
                result = compiler.compile(method, entryBCI, useProfilingInfo, shouldRetainLocalVariables, compilationId, debug);
            } catch (Throwable e) {
                throw debug.handle(e);
            }

            if (result != null) {
                try (DebugCloseable b = CodeInstallationTime.start(debug)) {
                    installMethod(debug, result);
                }
                // Installation is included in compilation time and memory usage reported by printer
                printer.finish(result);
            }
            stats.finish(method, installedCode);
            if (result != null) {
                return HotSpotCompilationRequestResult.success(result.getBytecodeSize() - method.getCodeSize());
            }
            return null;
        }

    }

    public CompilationTask(HotSpotJVMCIRuntime jvmciRuntime, HotSpotGraalCompiler compiler, HotSpotCompilationRequest request, boolean useProfilingInfo, boolean installAsDefault) {
        this(jvmciRuntime, compiler, request, useProfilingInfo, false, installAsDefault);
    }

    public CompilationTask(HotSpotJVMCIRuntime jvmciRuntime, HotSpotGraalCompiler compiler, HotSpotCompilationRequest request, boolean useProfilingInfo, boolean shouldRetainLocalVariables,
                    boolean installAsDefault) {
        this.jvmciRuntime = jvmciRuntime;
        this.compiler = compiler;
        this.compilationId = new HotSpotCompilationIdentifier(request);
        this.useProfilingInfo = useProfilingInfo;
        this.shouldRetainLocalVariables = shouldRetainLocalVariables;
        this.installAsDefault = installAsDefault;
    }

    public OptionValues filterOptions(OptionValues options) {
        /*
         * Disable inlining if HotSpot has it disabled unless it's been explicitly set in Graal.
         */
        HotSpotGraalRuntimeProvider graalRuntime = compiler.getGraalRuntime();
        GraalHotSpotVMConfig config = graalRuntime.getVMConfig();
        OptionValues newOptions = options;
        if (!config.inline) {
            EconomicMap<OptionKey<?>, Object> m = OptionValues.newOptionMap();
            if (Inline.getValue(options) && !Inline.hasBeenSet(options)) {
                m.put(Inline, false);
            }
            if (InlineDuringParsing.getValue(options) && !InlineDuringParsing.hasBeenSet(options)) {
                m.put(InlineDuringParsing, false);
            }
            if (!m.isEmpty()) {
                newOptions = new OptionValues(options, m);
            }
        }
        return newOptions;
    }

    public HotSpotResolvedJavaMethod getMethod() {
        return getRequest().getMethod();
    }

    CompilationIdentifier getCompilationIdentifier() {
        return compilationId;
    }

    /**
     * Returns the HotSpot id of this compilation.
     *
     * @return HotSpot compile id
     */
    public int getId() {
        return getRequest().getId();
    }

    public int getEntryBCI() {
        return getRequest().getEntryBCI();
    }

    /**
     * @return the compilation id plus a trailing '%' if the compilation is an OSR to match
     *         PrintCompilation style output
     */
    public String getIdString() {
        if (getEntryBCI() != JVMCICompiler.INVOCATION_ENTRY_BCI) {
            return getId() + "%";
        } else {
            return Integer.toString(getId());
        }
    }

    public HotSpotInstalledCode getInstalledCode() {
        return installedCode;
    }

    /**
     * Time spent in compilation.
     */
    private static final TimerKey CompilationTime = DebugContext.timer("CompilationTime").doc("Time spent in compilation and code installation.");

    /**
     * Counts the number of compiled {@linkplain CompilationResult#getBytecodeSize() bytecodes}.
     */
    private static final CounterKey CompiledBytecodes = DebugContext.counter("CompiledBytecodes");

    /**
     * Counts the number of compiled {@linkplain CompilationResult#getBytecodeSize() bytecodes} for
     * which {@linkplain CompilationResult#getTargetCode()} code was installed.
     */
    private static final CounterKey CompiledAndInstalledBytecodes = DebugContext.counter("CompiledAndInstalledBytecodes");

    /**
     * Counts the number of installed {@linkplain CompilationResult#getTargetCodeSize()} bytes.
     */
    private static final CounterKey InstalledCodeSize = DebugContext.counter("InstalledCodeSize");

    /**
     * Time spent in code installation.
     */
    public static final TimerKey CodeInstallationTime = DebugContext.timer("CodeInstallation");

    public HotSpotCompilationRequestResult runCompilation(OptionValues initialOptions) {
        OptionValues options = filterOptions(initialOptions);
        SnippetReflectionProvider snippetReflection = compiler.getGraalRuntime().getHostProviders().getSnippetReflection();
        try (DebugContext debug = DebugContext.create(options, new GraalDebugHandlersFactory(snippetReflection))) {
            return runCompilation(debug);
        }
    }

    @SuppressWarnings("try")
    public HotSpotCompilationRequestResult runCompilation(DebugContext debug) {
        HotSpotGraalRuntimeProvider graalRuntime = compiler.getGraalRuntime();
        GraalHotSpotVMConfig config = graalRuntime.getVMConfig();
        int entryBCI = getEntryBCI();
        boolean isOSR = entryBCI != JVMCICompiler.INVOCATION_ENTRY_BCI;
        HotSpotResolvedJavaMethod method = getMethod();

        if (installAsDefault || isOSR) {
            // If there is already compiled code for this method on our level we simply return.
            // JVMCI compiles are always at the highest compile level, even in non-tiered mode so we
            // only need to check for that value.
            if (method.hasCodeAtLevel(entryBCI, config.compilationLevelFullOptimization)) {
                return HotSpotCompilationRequestResult.failure("Already compiled", false);
            }
            if (HotSpotGraalCompilerFactory.shouldExclude(method)) {
                return HotSpotCompilationRequestResult.failure("GraalCompileOnly excluded", false);
            }
        }

        HotSpotCompilationWrapper compilation = new HotSpotCompilationWrapper();
        try (DebugCloseable a = CompilationTime.start(debug)) {
            return compilation.run(debug);
        } finally {
            try {
                int compiledBytecodes = 0;
                int codeSize = 0;

                if (compilation.result != null) {
                    compiledBytecodes = compilation.result.getBytecodeSize();
                    CompiledBytecodes.add(debug, compiledBytecodes);
                    if (installedCode != null) {
                        codeSize = installedCode.getSize();
                        CompiledAndInstalledBytecodes.add(debug, compiledBytecodes);
                        InstalledCodeSize.add(debug, codeSize);
                    }
                }
            } catch (Throwable t) {
                return compilation.handleException(t);
            }
        }
    }

    @SuppressWarnings("try")
    private void installMethod(DebugContext debug, final CompilationResult compResult) {
        final CodeCacheProvider codeCache = jvmciRuntime.getHostJVMCIBackend().getCodeCache();
        HotSpotBackend backend = compiler.getGraalRuntime().getHostBackend();
        installedCode = null;
        Object[] context = {new DebugDumpScope(getIdString(), true), codeCache, getMethod(), compResult};
        try (DebugContext.Scope s = debug.scope("CodeInstall", context)) {
            HotSpotCompilationRequest request = getRequest();
            installedCode = (HotSpotInstalledCode) backend.createInstalledCode(debug,
                            request.getMethod(),
                            request,
                            compResult,
                            null,
                            installAsDefault,
                            context);
        } catch (Throwable e) {
            throw debug.handle(e);
        }
    }

    @Override
    public String toString() {
        return "Compilation[id=" + getId() + ", " + getMethod().format("%H.%n(%p)") + (getEntryBCI() == JVMCICompiler.INVOCATION_ENTRY_BCI ? "" : "@" + getEntryBCI()) + "]";
    }

    private HotSpotCompilationRequest getRequest() {
        return compilationId.getRequest();
    }
}