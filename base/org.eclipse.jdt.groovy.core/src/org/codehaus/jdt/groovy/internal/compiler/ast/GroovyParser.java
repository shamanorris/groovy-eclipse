/*
 * Copyright 2009-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.jdt.groovy.internal.compiler.ast;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovyClassLoader;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilationUnit.ProgressListener;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.jdt.groovy.control.EclipseSourceUnit;
import org.codehaus.jdt.groovy.integration.internal.GroovyLanguageSupport;
import org.codehaus.jdt.groovy.internal.compiler.GroovyClassLoaderFactory;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.util.CompilerUtils;
import org.eclipse.jdt.groovy.core.util.GroovyUtils;
import org.eclipse.jdt.groovy.core.util.ReflectionUtils;
import org.eclipse.jdt.groovy.core.util.ScriptFolderSelector;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.batch.BatchCompilerRequestor;
import org.eclipse.jdt.internal.compiler.batch.Main;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.internal.core.builder.AbstractImageBuilder;
import org.eclipse.jdt.internal.core.builder.BuildNotifier;
import org.eclipse.jdt.internal.core.builder.SourceFile;

/**
 * The mapping layer between the groovy parser and the JDT. This class communicates
 * with the groovy parser and translates results back for JDT to consume.
 */
public class GroovyParser {

    public Object requestor;
    private JDTResolver resolver;
    public final ProblemReporter problemReporter;
    public static IGroovyDebugRequestor debugRequestor;
    private final GroovyClassLoaderFactory loaderFactory;

    private CompilationUnit compilationUnit;
    private CompilerOptions compilerOptions;

    public CompilerOptions getCompilerOptions() {
        return compilerOptions;
    }

    private static Map<String, ScriptFolderSelector> scriptFolderSelectorCache = new ConcurrentHashMap<>();

    public static void clearCache(String projectName) {
        scriptFolderSelectorCache.remove(projectName);
        GroovyClassLoaderFactory.clearCache(projectName);
    }

    //--------------------------------------------------------------------------

    public GroovyParser(CompilerOptions compilerOptions, ProblemReporter problemReporter, boolean allowTransforms, boolean isReconcile) {
        this(null, compilerOptions, problemReporter, allowTransforms, isReconcile);
    }

    public GroovyParser(Object requestor, CompilerOptions compilerOptions, ProblemReporter problemReporter, boolean allowTransforms, boolean isReconcile) {
        // FIXASC review callers who pass null for options
        // FIXASC set parent of the loader to system or context class loader?

        // record any paths we use for a project so that when the project is cleared,
        // the paths (which point to cached classloaders) can be cleared

        this.requestor = requestor;
        this.compilerOptions = compilerOptions;
        this.problemReporter = problemReporter;
        this.loaderFactory = new GroovyClassLoaderFactory(compilerOptions, requestor);

        // 2011-10-18: Status of transforms and reconciling
        // Prior to 2.6.0 all transforms were turned OFF for reconciling, and by turned off that meant no phase
        // processing for them was done at all. With 2.6.0 this phase processing is now active during reconciling
        // but it is currently limited to only allowing the Grab (global) transform to run. (Not sure why Grab
        // is a global transform... isn't it always annotation driven). Non-global transforms are all off.
        // This means the transformLoader is setup for the compilation unit but the cu is also told the
        // allowTransforms setting so it can decide what should be allowed through.
        // ---
        // Basic grab support: the design here is that a special classloader is created that will be augmented
        // with URLs when grab processing is running. This classloader is used as a last resort when resolving
        // types and is *only* called if a grab has occurred somewhere during compilation.
        // Currently it is not cached but created each time - we'll have to decide if there is a need to cache

        compilationUnit = newCompilationUnit(isReconcile, allowTransforms);
    }

    public void reset() {
        compilationUnit = newCompilationUnit(compilationUnit.isReconcile, compilationUnit.allowTransforms);
    }

    private CompilationUnit newCompilationUnit(boolean isReconcile, boolean allowTransforms) {
        CompilerConfiguration compilerConfiguration = GroovyLanguageSupport.newCompilerConfiguration(compilerOptions, problemReporter);
        GroovyClassLoader[] classLoaders = loaderFactory.getGroovyClassLoaders(compilerConfiguration);
        CompilationUnit cu = new CompilationUnit(
            compilerConfiguration,
            null, // CodeSource
            classLoaders[0],
            classLoaders[1],
            allowTransforms,
            compilerOptions.groovyExcludeGlobalASTScan);
        this.resolver = new JDTResolver(cu);
        cu.removeOutputPhaseOperation();
        cu.setResolveVisitor(resolver);
        cu.tweak(isReconcile);

        // GRAILS add
        if (allowTransforms && compilerOptions != null && (compilerOptions.groovyFlags & CompilerUtils.IsGrails) != 0) {
            cu.addPhaseOperation(new GrailsInjector(classLoaders[1]), Phases.CANONICALIZATION);
            new Grails20TestSupport(compilerOptions, classLoaders[1]).addGrailsTestCompilerCustomizers(cu);
            cu.addPhaseOperation(new GrailsGlobalPluginAwareEntityInjector(classLoaders[1]), Phases.CANONICALIZATION);
        }
        // GRAILS end

        return cu;
    }

    public CompilationUnitDeclaration dietParse(ICompilationUnit iCompilationUnit, CompilationResult compilationResult) {
        String fileName = String.valueOf(iCompilationUnit.getFileName());
        IPath filePath = new Path(fileName); IFile eclipseFile = null;
        // try to turn this into a 'real' absolute file system reference (this is because Grails 1.5 expects it)
        // GRECLIPSE-1269 ensure get plugin is not null to ensure the workspace is open (ie- not in batch mode)
        // Needs 2 segments: a project and file name or eclipse throws assertion failed here
        if (filePath.segmentCount() > 1 && ResourcesPlugin.getPlugin() != null) {
            eclipseFile = ResourcesPlugin.getWorkspace().getRoot().getFile(filePath);
            IPath location = eclipseFile.getLocation();
            if (location != null) {
                fileName = location.toFile().getAbsolutePath();
            }
        }

        char[] sourceCode = iCompilationUnit.getContents();
        if (sourceCode == null) {
            sourceCode = CharOperation.NO_CHAR;
        }

        SourceUnit sourceUnit = new EclipseSourceUnit(eclipseFile, fileName, String.valueOf(sourceCode), compilationUnit.isReconcile,
            compilationUnit.getConfiguration(), compilationUnit.getClassLoader(), new GroovyErrorCollectorForJDT(compilationUnit.getConfiguration()), resolver);

        compilationUnit.addSource(sourceUnit);

        if (requestor instanceof Compiler) {
            Compiler compiler = (Compiler) requestor;
            if (compiler.requestor instanceof AbstractImageBuilder) {
                AbstractImageBuilder builder = (AbstractImageBuilder) compiler.requestor;
                if (builder.notifier != null) {
                    compilationUnit.setProgressListener(new ProgressListenerImpl(builder.notifier));
                }
                if (eclipseFile != null) {
                    SourceFile sourceFile = (SourceFile) builder.fromIFile(eclipseFile);
                    if (sourceFile != null) {
                        compilationUnit.getConfiguration().setTargetDirectory(sourceFile.getOutputLocation().toFile());
                    }
                }
            } else if (compiler.requestor instanceof BatchCompilerRequestor) {
                Main main = ReflectionUtils.getPrivateField(BatchCompilerRequestor.class, "compiler", compiler.requestor);
                if (main != null && main.destinationPath != null && main.destinationPath != Main.NONE) {
                    compilationUnit.getConfiguration().setTargetDirectory(main.destinationPath);
                }
            }
        }

        compilationResult.lineSeparatorPositions = GroovyUtils.getSourceLineSeparatorsIn(sourceCode); // TODO: Get from Antlr

        GroovyCompilationUnitDeclaration gcuDeclaration = new GroovyCompilationUnitDeclaration(
            problemReporter, compilationResult, sourceCode.length, compilationUnit, sourceUnit, compilerOptions);

        gcuDeclaration.processToPhase(Phases.CONVERSION);

        // ModuleNode is null when there is a fatal error
        if (gcuDeclaration.getModuleNode() != null) {
            gcuDeclaration.populateCompilationUnitDeclaration();
            for (TypeDeclaration decl : gcuDeclaration.types) {
                resolver.record((GroovyTypeDeclaration) decl);
            }
        }
        String projectName = compilerOptions.groovyProjectName;
        // Is this a script? If allowTransforms is TRUE then this is a 'full build' and we should remember which are scripts so that .class file output can be suppressed
        if (projectName != null && eclipseFile != null) {
            ScriptFolderSelector scriptFolderSelector = scriptFolderSelectorCache.computeIfAbsent(projectName, GroovyParser::newScriptFolderSelector);
            if (scriptFolderSelector.isScript(eclipseFile)) {
                gcuDeclaration.tagAsScript();
            }
        }
        if (debugRequestor != null) {
            debugRequestor.acceptCompilationUnitDeclaration(gcuDeclaration);
        }
        return gcuDeclaration;
    }

    private static ScriptFolderSelector newScriptFolderSelector(String projectName) {
        return new ScriptFolderSelector(ResourcesPlugin.getWorkspace().getRoot().getProject(projectName));
    }

    /**
     * ProgressListener is called back when parsing of a file or generation of a classfile completes. By calling back to the build
     * notifier we ignore those long pauses where it look likes it has hung!
     *
     * Note: this does not move the progress bar, it merely updates the text
     */
    private static class ProgressListenerImpl implements ProgressListener {

        private BuildNotifier notifier;

        ProgressListenerImpl(BuildNotifier notifier) {
            this.notifier = notifier;
        }

        @Override
        public void parseComplete(int phase, String sourceUnitName) {
            try {
                // Chop it down to the containing package folder
                int lastSlash = sourceUnitName.lastIndexOf("/");
                if (lastSlash == -1) {
                    lastSlash = sourceUnitName.lastIndexOf("\\");
                }
                if (lastSlash != -1) {
                    StringBuffer msg = new StringBuffer();
                    msg.append("Parsing groovy source in ");
                    msg.append(sourceUnitName, 0, lastSlash);
                    notifier.subTask(msg.toString());
                }
            } catch (Exception e) {
                // doesn't matter
            }
            notifier.checkCancel();
        }

        @Override
        public void generateComplete(int phase, ClassNode classNode) {
            try {
                String pkgName = classNode.getPackageName();
                if (pkgName != null && pkgName.length() > 0) {
                    StringBuffer msg = new StringBuffer();
                    msg.append("Generating groovy classes in ");
                    msg.append(pkgName);
                    notifier.subTask(msg.toString());
                }
            } catch (Exception e) {
                // doesn't matter
            }
            notifier.checkCancel();
        }
    }
}
