package com.sam.besameditor.analysis;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.lang.model.SourceVersion;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class JavaSourceAnalyzerPrivateCoverageTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @Test
    void analyze_ShouldThrowWhenCompilerUnavailable() {
        try (MockedStatic<ToolProvider> toolProvider = mockStatic(ToolProvider.class)) {
            toolProvider.when(ToolProvider::getSystemJavaCompiler).thenReturn(null);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> analyzer.analyze("Sample.java", "class Sample {}")
            );

            assertEquals("JDK compiler is not available in the current runtime", exception.getMessage());
        }
    }

    @Test
    void analyze_ShouldWrapCloseFailure() {
        JavaCompiler realCompiler = ToolProvider.getSystemJavaCompiler();
        if (realCompiler == null) {
            throw new IllegalStateException("JDK compiler is not available in the current runtime");
        }

        JavaCompiler failingCloseCompiler = new JavaCompiler() {
            @Override
            public StandardJavaFileManager getStandardFileManager(DiagnosticListener<? super JavaFileObject> diagnosticListener,
                                                                  Locale locale,
                                                                  Charset charset) {
                StandardJavaFileManager delegate = realCompiler.getStandardFileManager(diagnosticListener, locale, charset);
                return (StandardJavaFileManager) Proxy.newProxyInstance(
                        StandardJavaFileManager.class.getClassLoader(),
                        new Class[]{StandardJavaFileManager.class},
                        (proxy, method, args) -> {
                            if ("close".equals(method.getName())) {
                                throw new java.io.IOException("close failure");
                            }
                            return method.invoke(delegate, args);
                        });
            }

            @Override
            public CompilationTask getTask(Writer out,
                                           JavaFileManager fileManager,
                                           DiagnosticListener<? super JavaFileObject> diagnosticListener,
                                           Iterable<String> options,
                                           Iterable<String> classes,
                                           Iterable<? extends JavaFileObject> compilationUnits) {
                return realCompiler.getTask(out, fileManager, diagnosticListener, options, classes, compilationUnits);
            }

            @Override
            public int run(InputStream in, OutputStream out, OutputStream err, String... arguments) {
                return realCompiler.run(in, out, err, arguments);
            }

            @Override
            public Set<SourceVersion> getSourceVersions() {
                return realCompiler.getSourceVersions();
            }

            @Override
            public int isSupportedOption(String option) {
                return realCompiler.isSupportedOption(option);
            }
        };

        try (MockedStatic<ToolProvider> toolProvider = mockStatic(ToolProvider.class)) {
            toolProvider.when(ToolProvider::getSystemJavaCompiler).thenReturn(failingCloseCompiler);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> analyzer.analyze("Sample.java", "class Sample { void run() {} }")
            );

            assertEquals("Unable to close Java compiler resources", exception.getMessage());
        }
    }

    @Test
    void privateHelpers_ShouldCoverSignaturePositionsAndDiagnostics() {
        MethodTree methodTree = mock(MethodTree.class);
        Name methodName = mock(Name.class);
        when(methodName.toString()).thenReturn("run");
        when(methodTree.getReturnType()).thenReturn(null);
        when(methodTree.getParameters()).thenReturn(List.of());
        when(methodTree.getName()).thenReturn(methodName);

        assertEquals("void run()", invoke(analyzer, "buildSignature", new Class[]{MethodTree.class}, methodTree));
        assertEquals(1, invoke(analyzer, "lineOf", new Class[]{CompilationUnitTree.class, long.class}, mock(CompilationUnitTree.class), Diagnostic.NOPOS));
        assertEquals(1, invoke(analyzer, "lineOf", new Class[]{CompilationUnitTree.class, long.class}, mock(CompilationUnitTree.class), -1L));

        CompilationUnitTree compilationUnit = mock(CompilationUnitTree.class);
        SourcePositions sourcePositions = mock(SourcePositions.class);
        Tree tree = mock(Tree.class);
        when(sourcePositions.getEndPosition(compilationUnit, tree)).thenReturn(Diagnostic.NOPOS);
        when(sourcePositions.getStartPosition(compilationUnit, tree)).thenReturn(Diagnostic.NOPOS);
        assertEquals(1, invoke(analyzer, "endLineOf", new Class[]{CompilationUnitTree.class, SourcePositions.class, Tree.class}, compilationUnit, sourcePositions, tree));

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        diagnostics.report(new SimpleDiagnostic(Diagnostic.Kind.WARNING, 1L, 1L, "warning only"));
        assertDoesNotThrow(() -> invoke(analyzer, "assertNoSyntaxErrors", new Class[]{DiagnosticCollector.class}, diagnostics));

        CaseTree defaultCase = mock(CaseTree.class);
        when(defaultCase.getExpressions()).thenReturn(Collections.emptyList());
        assertTrue((Boolean) invokeStatic(JavaSourceAnalyzer.class, "isDefaultCase", new Class[]{CaseTree.class}, defaultCase));

        CaseTree labeledCase = mock(CaseTree.class);
        when(labeledCase.getExpressions()).thenReturn((List) List.of(mock(ExpressionTree.class)));
        assertEquals(false, invokeStatic(JavaSourceAnalyzer.class, "isDefaultCase", new Class[]{CaseTree.class}, labeledCase));
    }

    @Test
    void builderHelpers_ShouldCoverNullTargetsAndSnippetFallbacks() throws Exception {
        CompilationUnitTree compilationUnit = mock(CompilationUnitTree.class);
        SourcePositions sourcePositions = mock(SourcePositions.class);
        LineMap lineMap = mock(LineMap.class);
        when(compilationUnit.getLineMap()).thenReturn(lineMap);
        when(lineMap.getLineNumber(anyLong())).thenReturn(7L);

        Object builder = newBuilder(compilationUnit, sourcePositions, "   abcdefghijklmnopqrstuvwxyz   ");
        Object controlContext = newControlContext(null, null);
        Tree tree = mock(Tree.class);
        when(sourcePositions.getStartPosition(compilationUnit, tree)).thenReturn(Diagnostic.NOPOS);
        when(sourcePositions.getEndPosition(compilationUnit, tree)).thenReturn(Diagnostic.NOPOS);

        assertEquals("fallback", invoke(builder, "renderSnippet", new Class[]{Tree.class, String.class}, null, "fallback"));
        assertEquals("fallback", invoke(builder, "renderSnippet", new Class[]{Tree.class, String.class}, tree, "fallback"));

        Tree invalidTree = mock(Tree.class);
        when(sourcePositions.getStartPosition(compilationUnit, invalidTree)).thenReturn(5L);
        when(sourcePositions.getEndPosition(compilationUnit, invalidTree)).thenReturn(4L);
        assertEquals("fallback", invoke(builder, "renderSnippet", new Class[]{Tree.class, String.class}, invalidTree, "fallback"));

        Tree oversizedTree = mock(Tree.class);
        when(sourcePositions.getStartPosition(compilationUnit, oversizedTree)).thenReturn(0L);
        when(sourcePositions.getEndPosition(compilationUnit, oversizedTree)).thenReturn(999L);
        assertEquals("fallback", invoke(builder, "renderSnippet", new Class[]{Tree.class, String.class}, oversizedTree, "fallback"));

        Tree blankTree = mock(Tree.class);
        when(sourcePositions.getStartPosition(compilationUnit, blankTree)).thenReturn(0L);
        when(sourcePositions.getEndPosition(compilationUnit, blankTree)).thenReturn(2L);
        assertEquals("fallback", invoke(builder, "renderSnippet", new Class[]{Tree.class, String.class}, blankTree, "fallback"));

        Object longSnippetBuilder = newBuilder(compilationUnit, sourcePositions, "x".repeat(120));
        Tree longTree = mock(Tree.class);
        when(sourcePositions.getStartPosition(compilationUnit, longTree)).thenReturn(0L);
        when(sourcePositions.getEndPosition(compilationUnit, longTree)).thenReturn(120L);
        String longSnippet = (String) invoke(longSnippetBuilder, "renderSnippet", new Class[]{Tree.class, String.class}, longTree, "fallback");
        assertTrue(longSnippet.endsWith("..."));

        invoke(builder, "buildBreak", new Class[]{Tree.class, controlContext.getClass()}, tree, controlContext);
        invoke(builder, "buildContinue", new Class[]{Tree.class, controlContext.getClass()}, tree, controlContext);
        invoke(builder, "buildStatement", new Class[]{StatementTree.class, controlContext.getClass(), String.class}, null, controlContext, "exit");
        invoke(builder, "buildSequence", new Class[]{List.class, controlContext.getClass(), String.class}, List.of(), controlContext, "exit");

        StatementTree statementTree = mock(StatementTree.class);
        when(statementTree.getKind()).thenReturn(Tree.Kind.EXPRESSION_STATEMENT);
        invoke(builder, "buildStatement", new Class[]{StatementTree.class, controlContext.getClass(), String.class}, statementTree, controlContext, "exit");

        assertEquals(1, invoke(builder, "lineOf", new Class[]{long.class}, Diagnostic.NOPOS));
        assertEquals(1, invoke(builder, "lineOf", new Class[]{long.class}, -1L));
        assertEquals(1, invoke(builder, "endLineOf", new Class[]{Tree.class}, tree));
    }

    private Object newBuilder(CompilationUnitTree compilationUnit, SourcePositions sourcePositions, String sourceCode) throws Exception {
        Class<?> builderClass = Class.forName("com.sam.besameditor.analysis.JavaSourceAnalyzer$ControlFlowGraphBuilder");
        Constructor<?> constructor = builderClass.getDeclaredConstructor(CompilationUnitTree.class, SourcePositions.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(compilationUnit, sourcePositions, sourceCode);
    }

    private Object newControlContext(String breakTarget, String continueTarget) throws Exception {
        Class<?> contextClass = Class.forName("com.sam.besameditor.analysis.JavaSourceAnalyzer$ControlFlowGraphBuilder$ControlContext");
        Constructor<?> constructor = contextClass.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(breakTarget, continueTarget);
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object invokeStatic(Class<?> targetType, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = targetType.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record SimpleDiagnostic(Diagnostic.Kind kind, long line, long column, String message) implements Diagnostic<JavaFileObject> {
        @Override
        public Kind getKind() {
            return kind;
        }

        @Override
        public JavaFileObject getSource() {
            return null;
        }

        @Override
        public long getPosition() {
            return 0;
        }

        @Override
        public long getStartPosition() {
            return 0;
        }

        @Override
        public long getEndPosition() {
            return 0;
        }

        @Override
        public long getLineNumber() {
            return line;
        }

        @Override
        public long getColumnNumber() {
            return column;
        }

        @Override
        public String getCode() {
            return "TEST";
        }

        @Override
        public String getMessage(Locale locale) {
            return message;
        }
    }
}
