/*
 * Copyright 2026 The SWTImageJ LLM Assistant authors
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
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package llmassistant;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import ij.IJ;
import ij.Menus;
import ij.plugin.PlugIn;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;

/**
 * Compiles and runs Java code inside SWTImageJ, in memory, with the compiler
 * messages and exceptions returned as text (for the model to fix its code).
 * <p>
 * Accepts either a complete class (implementing PlugIn or PlugInFilter, or with
 * a static main method) or a snippet of statements, which is wrapped into a
 * PlugIn with common ImageJ imports. The classpath is built from the actual
 * locations of the ImageJ, SWT and plugin classes, so it also works when
 * SWTImageJ runs as an Eclipse/OSGi application (where java.class.path does
 * not contain them). Needs a JDK (javax.tools compiler) at runtime.
 */
public final class JavaRunner {

	private static final Pattern CLASS_DECL = Pattern.compile("(?m)^[ \\t]*(?:(?:public|final|abstract)\\s+)*class\\s+([A-Za-z_$][\\w$]*)");
	private static final Pattern PUBLIC_CLASS = Pattern.compile("(?m)^[ \\t]*public\\s+(?:(?:final|abstract)\\s+)*class\\s+([A-Za-z_$][\\w$]*)");
	private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
	private static final AtomicInteger COUNTER = new AtomicInteger();

	/** Imports used for snippets (single-type imports resolve java.util.List vs. SWT List etc.). */
	private static final String[] SNIPPET_IMPORTS = {"ij.*", "ij.gui.*", "ij.process.*", "ij.measure.*", "ij.plugin.*", "ij.plugin.filter.*", "ij.plugin.frame.*", "ij.io.*", "ij.text.*", "ij.macro.*", "java.util.*", "java.io.*", "java.util.List", "java.util.ArrayList", "java.util.Map", "java.util.HashMap", "java.io.File", "org.eclipse.swt.SWT", "org.eclipse.swt.widgets.Display", "org.eclipse.swt.widgets.Shell"};

	private JavaRunner() {
	}

	/** Compiler output. */
	public static final class Compiled {

		public boolean ok;
		public String mainClass;
		public String diagnostics = "";
		public final Map<String, byte[]> classes = new HashMap<>();
		/** Compiler errors/warnings with line (in the user's code), column and length, for editor markers. */
		public final List<EditorMarkers.Issue> issues = new ArrayList<>();
		/** Number of leading import/blank lines of a snippet (kept at file level). */
		public int headLines;
		public boolean snippet;
	}

	/* ================================================================ source analysis */

	public static boolean isCompleteClass(String code) {
		return CLASS_DECL.matcher(stripComments(code)).find();
	}

	/** Simple name of the public (or first) top-level class, or null. */
	public static String className(String code) {
		String c = stripComments(code);
		Matcher m = PUBLIC_CLASS.matcher(c);
		if(m.find())
			return m.group(1);
		m = CLASS_DECL.matcher(c);
		return m.find() ? m.group(1) : null;
	}

	public static String packageName(String code) {
		Matcher m = PACKAGE.matcher(stripComments(code));
		return m.find() ? m.group(1) : null;
	}

	private static String stripComments(String s) {
		return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	/* ================================================================ classpath */

	/** Classpath for javac: runtime class path + locations of ImageJ, SWT, this plugin + plugin jars. */
	static String classPath() {
		Set<String> cp = new LinkedHashSet<>();
		String jcp = System.getProperty("java.class.path");
		if(jcp != null)
			for(String p : jcp.split(File.pathSeparator))
				if(!p.isBlank())
					cp.add(p);
		for(Class<?> c : new Class<?>[]{IJ.class, org.eclipse.swt.widgets.Display.class, JavaRunner.class})
			addLocation(c, cp);
		String plugins = Menus.getPlugInsPath();
		if(plugins != null) {
			cp.add(new File(plugins).getAbsolutePath());
			addJars(new File(plugins), cp, 0);
		}
		return String.join(File.pathSeparator, cp);
	}

	private static void addLocation(Class<?> c, Set<String> cp) {
		try {
			URL u = c.getProtectionDomain().getCodeSource().getLocation();
			File f = toFile(u);
			if(f != null) {
				cp.add(f.getAbsolutePath());
				File bin = new File(f, "bin"); // Eclipse development layout
				if(f.isDirectory() && bin.isDirectory())
					cp.add(bin.getAbsolutePath());
				return;
			}
		} catch(Throwable ignored) {
		}
		// fall back to the class file resource: jar:file:/x.jar!/ij/IJ.class or file:/dir/ij/IJ.class
		try {
			String res = c.getName().replace('.', '/') + ".class";
			URL u = c.getClassLoader().getResource(res);
			if(u == null)
				return;
			File f = toFile(u);
			if(f == null)
				return;
			String p = f.getAbsolutePath();
			if(p.endsWith(res.replace('/', File.separatorChar)))
				p = p.substring(0, p.length() - res.length());
			cp.add(p);
		} catch(Throwable ignored) {
		}
	}

	/** file:, jar:file:...!/, and (via Equinox FileLocator, if present) bundleresource:/bundleentry: URLs. */
	private static File toFile(URL u) throws Exception {
		if(u == null)
			return null;
		String proto = u.getProtocol();
		if("bundleresource".equals(proto) || "bundleentry".equals(proto)) {
			try {
				Class<?> fl = Class.forName("org.eclipse.core.runtime.FileLocator");
				u = (URL)fl.getMethod("resolve", URL.class).invoke(null, u);
				proto = u.getProtocol();
			} catch(Throwable t) {
				return null;
			}
		}
		if("jar".equals(proto)) {
			String s = u.getPath(); // file:/x.jar!/entry
			int bang = s.indexOf("!/");
			return toFile(URI.create(bang >= 0 ? s.substring(0, bang) : s).toURL());
		}
		if("file".equals(proto)) {
			try {
				return Paths.get(u.toURI()).toFile();
			} catch(Exception e) {
				return new File(u.getPath());
			}
		}
		return null;
	}

	/** Same rule as ij.plugin.Compiler: jars without '_' anywhere, and all jars in jars/ or lib/ folders. */
	private static void addJars(File dir, Set<String> cp, int depth) {
		File[] list = dir.listFiles();
		if(list == null || depth > 4)
			return;
		boolean libFolder = dir.getName().equals("jars") || dir.getName().equals("lib");
		for(File f : list) {
			if(f.isDirectory())
				addJars(f, cp, depth + 1);
			else if(f.getName().endsWith(".jar") && (!f.getName().contains("_") || libFolder))
				cp.add(f.getAbsolutePath());
		}
	}

	/* ================================================================ compiling */

	private static final class Source extends SimpleJavaFileObject {

		private final String code;

		Source(String binaryName, String code) {
			super(URI.create("string:///" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return code;
		}
	}

	private static final class Output extends SimpleJavaFileObject {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final String name;

		Output(String name) {
			super(URI.create("mem:///" + name.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
			this.name = name;
		}

		@Override
		public OutputStream openOutputStream() {
			return bytes;
		}
	}

	/**
	 * Compiles the code in memory. Snippets are wrapped into a PlugIn class with
	 * a unique name.
	 */
	public static Compiled compile(String code) {
		Compiled out = new Compiled();
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if(javac == null) {
			out.diagnostics = "No Java compiler available: SWTImageJ is running on a Java runtime (JRE) without javac. Start SWTImageJ with a JDK 21+ to compile Java. Macros and scripts still work.";
			return out;
		}
		String source;
		String pkg;
		if(isCompleteClass(code)) {
			source = code;
			pkg = packageName(code);
			out.mainClass = (pkg == null ? "" : pkg + ".") + className(code);
		} else {
			out.snippet = true;
			pkg = null;
			String name = "LLM_Snippet_" + COUNTER.incrementAndGet();
			/* the leading block of import (and blank) lines stays at file level, the rest becomes the method body */
			String[] lines = code.split("\n", -1);
			int headCount = 0;
			while(headCount < lines.length && (lines[headCount].isBlank() || lines[headCount].trim().startsWith("import ")))
				headCount++;
			StringBuilder wrapper = new StringBuilder();
			for(String imp : SNIPPET_IMPORTS)
				wrapper.append("import ").append(imp).append("; ");
			wrapper.append('\n'); // wrapper line 1
			for(int i = 0; i < headCount; i++)
				wrapper.append(lines[i]).append('\n'); // wrapper lines 2 .. 1+headCount
			wrapper.append("public class ").append(name).append(" implements ij.plugin.PlugIn {\n");
			wrapper.append("public void run(String arg) { try { __body(); } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new RuntimeException(e); } }\n");
			wrapper.append("private void __body() throws Exception {\n");
			for(int i = headCount; i < lines.length; i++)
				wrapper.append(lines[i]).append('\n'); // wrapper line 5+headCount+k = user line headCount+1+k
			wrapper.append("\n}\n}\n");
			source = wrapper.toString();
			out.mainClass = name;
			out.headLines = headCount;
		}
		DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
		StandardJavaFileManager std = javac.getStandardFileManager(diag, null, StandardCharsets.UTF_8);
		List<Output> outputs = new ArrayList<>();
		JavaFileManager fm = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {

			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
				Output o = new Output(className);
				outputs.add(o);
				return o;
			}
		};
		List<String> options = List.of("-g", "-proc:none", "-encoding", "UTF-8", "-Xlint:-options", "-classpath", classPath());
		StringWriter log = new StringWriter();
		boolean ok;
		try {
			ok = javac.getTask(log, fm, diag, options, null, List.of(new Source(out.mainClass, source))).call();
		} catch(Throwable t) {
			ok = false;
			log.append(t.toString());
		}
		StringBuilder sb = new StringBuilder();
		for(Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
			if(d.getKind() != Diagnostic.Kind.ERROR && d.getKind() != Diagnostic.Kind.WARNING && d.getKind() != Diagnostic.Kind.MANDATORY_WARNING)
				continue;
			long line = d.getLineNumber();
			if(out.snippet && line > 0)
				line = mapSnippetLine(out, line);
			if(line > 0) {
				EditorMarkers.Issue is = EditorMarkers.Issue.at((int)line, d.getKind() == Diagnostic.Kind.ERROR ? "error" : "warning", d.getMessage(null).split("\n", 2)[0]);
				long st = d.getStartPosition(), en = d.getEndPosition(), pos = d.getPosition();
				// getColumnNumber() refers to the preferred position (e.g. the '.' of a member access);
				// start the marker at the start of the whole expression, whose length is end - start
				long col = d.getColumnNumber() - (st >= 0 && pos >= st ? pos - st : 0);
				is.column = (int)Math.max(1, col);
				is.length = st >= 0 && en > st ? (int)(en - st) : -1;
				out.issues.add(is);
			}
			sb.append(d.getKind() == Diagnostic.Kind.ERROR ? "error" : "warning").append(line > 0 ? " at line " + line : "").append(": ").append(d.getMessage(null)).append('\n');
		}
		if(log.getBuffer().length() > 0)
			sb.append(log);
		out.diagnostics = sb.toString().strip();
		if(out.snippet)
			out.diagnostics = out.diagnostics.replace("class " + out.mainClass, "your snippet").replace(out.mainClass, "snippet");
		out.ok = ok;
		if(ok)
			for(Output o : outputs)
				out.classes.put(o.name, o.bytes.toByteArray());
		return out;
	}

	/** Maps a line of the snippet wrapper back to the line of the user's snippet. */
	static long mapSnippetLine(Compiled c, long w) {
		int h = c.headLines;
		if(w <= 1)
			return 1; // default imports
		if(w <= 1 + h)
			return w - 1; // user's import lines
		if(w >= 5 + h)
			return w - 4; // body
		return h + 1; // wrapper lines: report the first body line
	}

	/* ================================================================ running */

	/** Child-first loader for the freshly compiled classes, delegating everything else to ImageJ's plugin loader. */
	private static final class MemoryLoader extends ClassLoader {

		private final Map<String, byte[]> classes;

		MemoryLoader(Map<String, byte[]> classes, ClassLoader parent) {
			super(parent);
			this.classes = classes;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized(getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				if(c == null && classes.containsKey(name)) {
					byte[] b = classes.get(name);
					c = defineClass(name, b, 0, b.length);
				}
				if(c == null)
					return super.loadClass(name, resolve);
				if(resolve)
					resolveClass(c);
				return c;
			}
		}
	}

	/**
	 * Compiles and runs Java code on the calling thread. Returns null on success
	 * or an error text (compiler messages or the exception); compiler warnings are
	 * appended to {@code info}.
	 */
	public static String compileAndRun(String code, StringBuilder info) {
		return compileAndRun(code, info, null);
	}

	/** As above; compiler errors and the failing line of an exception are added to issues (if not null). */
	public static String compileAndRun(String code, StringBuilder info, List<EditorMarkers.Issue> issues) {
		Compiled c = compile(code);
		if(issues != null)
			issues.addAll(c.issues);
		if(!c.ok)
			return "Compilation failed" + (c.snippet ? " (snippet, line numbers refer to your code)" : "") + ":\n" + c.diagnostics;
		if(!c.diagnostics.isEmpty())
			info.append("Compiler warnings:\n").append(c.diagnostics).append('\n');
		try {
			ClassLoader parent = IJ.getClassLoader();
			if(parent == null)
				parent = JavaRunner.class.getClassLoader();
			Class<?> cls = new MemoryLoader(c.classes, parent).loadClass(c.mainClass);
			if(PlugIn.class.isAssignableFrom(cls)) {
				Object o = cls.getDeclaredConstructor().newInstance();
				((PlugIn)o).run("");
				info.append("Ran ").append(c.snippet ? "Java snippet" : cls.getSimpleName() + " (PlugIn)").append('\n');
			} else if(PlugInFilter.class.isAssignableFrom(cls)) {
				Object o = cls.getDeclaredConstructor().newInstance();
				new PlugInFilterRunner(o, cls.getSimpleName(), "");
				info.append("Ran ").append(cls.getSimpleName()).append(" (PlugInFilter on the active image)\n");
			} else {
				Method main = null;
				try {
					main = cls.getMethod("main", String[].class);
				} catch(NoSuchMethodException ignored) {
				}
				if(main == null || !Modifier.isStatic(main.getModifiers()))
					return "The class " + cls.getSimpleName() + " has no entry point: implement ij.plugin.PlugIn (run(String arg)), ij.plugin.filter.PlugInFilter, or add public static void main(String[] args).";
				main.invoke(null, (Object)new String[0]);
				info.append("Ran ").append(cls.getSimpleName()).append(".main()\n");
			}
			return null;
		} catch(Throwable t) {
			if(issues != null)
				addExceptionIssue(t, c, issues);
			return exceptionReport(t, c);
		}
	}

	/** Marks the first line of the user's code in the stack trace of an exception. */
	private static void addExceptionIssue(Throwable t, Compiled c, List<EditorMarkers.Issue> issues) {
		Throwable e = t;
		while(e instanceof InvocationTargetException && e.getCause() != null)
			e = e.getCause();
		for(Throwable x = e; x != null; x = x.getCause() == x ? null : x.getCause()) {
			for(StackTraceElement st : x.getStackTrace()) {
				boolean mine = c.classes.containsKey(st.getClassName()) || st.getClassName().startsWith(c.mainClass + "$");
				if(!mine || st.getLineNumber() <= 0 || (c.snippet && st.getMethodName().equals("run")))
					continue;
				int line = c.snippet ? (int)mapSnippetLine(c, st.getLineNumber()) : st.getLineNumber();
				issues.add(EditorMarkers.Issue.at(line, "error", "Runtime exception: " + x));
				return;
			}
		}
	}

	private static String exceptionReport(Throwable t, Compiled c) {
		Throwable e = t;
		while((e instanceof InvocationTargetException || (e instanceof RuntimeException && e.getCause() != null && e.getMessage() != null && e.getMessage().equals(e.getCause().toString()))) && e.getCause() != null)
			e = e.getCause();
		StringBuilder sb = new StringBuilder("Exception: ").append(e).append('\n');
		int shown = 0;
		for(StackTraceElement st : e.getStackTrace()) {
			boolean mine = c.classes.containsKey(st.getClassName()) || st.getClassName().startsWith(c.mainClass + "$");
			if(!mine && shown > 0 && !st.getClassName().startsWith("ij."))
				continue;
			if(mine && c.snippet && st.getMethodName().equals("run"))
				continue; // the generated PlugIn.run() wrapper
			int line = st.getLineNumber();
			if(mine && c.snippet && line > 0)
				line = (int)mapSnippetLine(c, line);
			sb.append("  at ").append(mine && c.snippet ? "snippet" : st.getClassName() + "." + st.getMethodName()).append(line > 0 ? " line " + line : "").append('\n');
			if(++shown >= 8)
				break;
		}
		if(e.getCause() != null && e.getCause() != e)
			sb.append("Caused by: ").append(e.getCause()).append('\n');
		return sb.toString().strip();
	}

	/* ================================================================ installing */

	/**
	 * Saves a complete class as a SWTImageJ plugin: writes the .java source and the
	 * compiled .class files into the plugins folder (subfolder, default
	 * "LLM_Plugins", or the package directory) and refreshes the menus.
	 *
	 * @return a report; starts with "ERROR" on failure
	 */
	public static String install(String code, String subfolder) throws Exception {
		if(!isCompleteClass(code))
			return "ERROR: save_java_plugin needs a complete class (e.g. public class My_Filter implements PlugIn { ... }).";
		String plugins = Menus.getPlugInsPath();
		if(plugins == null)
			return "ERROR: the plugins folder is unknown.";
		Compiled c = compile(code);
		if(!c.ok)
			return "ERROR: compilation failed, nothing was saved:\n" + c.diagnostics;
		String name = className(code);
		String pkg = packageName(code);
		File root = new File(plugins);
		File dir;
		if(pkg != null)
			dir = new File(root, pkg.replace('.', File.separatorChar));
		else
			dir = new File(root, subfolder == null || subfolder.isBlank() ? "LLM_Plugins" : subfolder.replaceAll("[^\\w-]", "_"));
		dir.mkdirs();
		File src = new File(dir, name + ".java");
		Files.write(src.toPath(), code.getBytes(StandardCharsets.UTF_8));
		for(Map.Entry<String, byte[]> e : c.classes.entrySet()) {
			String simple = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
			Files.write(new File(dir, simple + ".class").toPath(), e.getValue());
		}
		IJ.resetClassLoader();
		IJ.run("Refresh Menus");
		StringBuilder sb = new StringBuilder("OK: saved ").append(src.getAbsolutePath()).append(" and ").append(c.classes.size()).append(" class file(s).\n");
		if(name.contains("_") && pkg == null)
			sb.append("Menu: Plugins > ").append(dir.getName().replace('_', ' ')).append(" > ").append(name.replace('_', ' '));
		else
			sb.append("Note: ImageJ only lists plugins with an underscore in the class name (in the default package) in the Plugins menu; run it with IJ.runPlugIn(\"").append(pkg == null ? "" : pkg + ".").append(name).append("\", \"\").");
		if(!c.diagnostics.isEmpty())
			sb.append("\nCompiler warnings:\n").append(c.diagnostics);
		return sb.toString();
	}

	static String stackTrace(Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		return w.toString();
	}
}
