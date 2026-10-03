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

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Macro_Runner;
import ij.plugin.frame.Editor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * The SWTImageJ functionality the language model may call ("function calling"),
 * plus helpers to describe the current ImageJ state and to run macros/scripts.
 */
public class ImageJTools {

	/** UI callbacks needed by the tools (confirmation, progress). */
	public interface Host {

		/** Ask the user (blocking). */
		boolean confirm(String title, String message);

		/** Short progress / info line in the chat transcript. */
		void info(String text);

		/** Markers in an editor changed (e.g. to update UI state). */
		default void markersChanged() {
		}
	}

	private static final int MAX_RESULT = 12000;
	private final EditorBridge editors;
	private final LLMSettings settings;
	private final Host host;
	/** Snapshots requested by the model via view_image, sent with the next request. */
	private final List<Snapshot> pendingSnapshots = java.util.Collections.synchronizedList(new ArrayList<>());

	/** An image rendered as PNG for a vision model. */
	public static class Snapshot {

		public String title, dataUrl, note;
		public int width, height, originalWidth, originalHeight;

		/** OpenAI chat content part. */
		public Map<String, Object> contentPart() {
			return Json.obj("type", "image_url", "image_url", Json.obj("url", dataUrl, "detail", "high"));
		}

		public String describe() {
			return "\"" + title + "\" " + width + "x" + height + " px" + (width != originalWidth || height != originalHeight ? " (scaled from " + originalWidth + "x" + originalHeight + ")" : "") + (note == null ? "" : ", " + note);
		}
	}

	public ImageJTools(EditorBridge editors, LLMSettings settings, Host host) {
		this.editors = editors;
		this.settings = settings;
		this.host = host;
	}

	/* ================================================================ tool schema */

	private static Map<String, Object> fn(String name, String description, Map<String, Object> props, String... required) {
		Map<String, Object> params = Json.obj("type", "object", "properties", props == null ? Json.obj() : props);
		if(required.length > 0)
			params.put("required", Json.arr((Object[])required));
		return Json.obj("type", "function", "function", Json.obj("name", name, "description", description, "parameters", params));
	}

	private static Map<String, Object> p(String type, String desc) {
		return Json.obj("type", type, "description", desc);
	}

	public List<Object> definitions() {
		List<Object> t = new ArrayList<>();
		t.add(fn("get_imagej_context", "Overview of the SWTImageJ session: version, open images, the active image, open editors, results table size.", null));
		t.add(fn("get_active_image_info", "Detailed information about the active image: dimensions, type, calibration, ROI, pixel statistics, image info/metadata (truncated).", null));
		if(settings.enableVision)
			t.add(fn("view_image", "Lets you SEE an open image: renders it as displayed (LUT, contrast, overlay; current slice/channel composite) and attaches it as a picture to the conversation. Use it to judge image content, quality, segmentation or measurement results visually.",
					Json.obj("title", p("string", "Image title; default is the active image."), "crop_to_roi", p("boolean", "Only the bounding box of the current selection (default false)."), "max_size", p("integer", "Longest side in pixels after scaling (default from preferences)."))));
		t.add(fn("open_image", "Opens an image in SWTImageJ and makes it the active image. source can be a local file path, an http(s) URL, or the name of an ImageJ sample image (see list_sample_images).",
				Json.obj("source", p("string", "File path, URL or sample name, e.g. 'Blobs' or '/data/cells.tif'.")), "source"));
		t.add(fn("list_sample_images", "Lists the names of the ImageJ sample images (File > Open Samples) that open_image can open.", null));
		t.add(fn("select_image", "Makes the open image with the given title the active image.", Json.obj("title", p("string", "Image title.")), "title"));
		t.add(fn("get_editor_text", "Returns the full text and title (language by extension) of the target editor - by default the selected Script Explorer tab - plus the currently selected text and the markers the assistant placed.",
				Json.obj("line_numbers", p("boolean", "Prefix every line with its 1-based number (use this before mark_issues). Never copy these prefixes into code."))));
		if(EditorMarkers.available()) {
			Map<String, Object> issue = Json.obj("type", "object", "properties", Json.obj(
					"line", p("integer", "1-based line of the problem."),
					"end_line", p("integer", "Optional last line when the marker (and its replacement) spans several whole lines."),
					"match", p("string", "Optional exact text on that line to mark; the quick fix replaces only this text. Omit to mark the whole line(s)."),
					"occurrence", p("integer", "Which occurrence of match on the line (default 1)."),
					"severity", Json.obj("type", "string", "enum", Json.arr("error", "warning", "info")),
					"message", p("string", "Short explanation shown when hovering the marker."),
					"fix_description", p("string", "Short description of the quick fix, e.g. 'Add missing semicolon'."),
					"replacement", p("string", "Corrected text replacing the marked range (match, or the whole line(s) without the final line break). Omit if there is no automatic fix.")),
					"required", Json.arr("line", "message"));
			t.add(fn("mark_issues", "Marks problems in the target editor with error/warning/info markers (squiggly underline + ruler dot, message on hover) and optional quick fixes. The user applies a quick fix by double-clicking the marked text, so do NOT rewrite the editor yourself when using this. Call get_editor_text with line_numbers=true first.",
					Json.obj("issues", Json.obj("type", "array", "items", issue), "clear_previous", p("boolean", "Remove existing markers first (default true).")), "issues"));
			t.add(fn("check_code", "Checks the target editor without running it: Java is compiled (errors/warnings are marked in the editor and returned with line, column and the line text). Other languages cannot be checked without running - review them yourself and use mark_issues, or run_editor, which marks runtime errors.", null));
			t.add(fn("apply_quick_fixes", "Applies all quick fixes the assistant placed in the target editor (bottom to top). Only when the user asked to apply them. The user may be asked to approve.", null));
			t.add(fn("clear_markers", "Removes all markers from the target editor.", null));
		}
		t.add(fn("set_editor_text", "Writes code into the target editor. mode: 'replace' (whole content), 'insert' (at caret / replace selection), 'append' (at end) or 'new' (opens a new Script Explorer tab, or editor window if the explorer is unavailable; title with extension .ijm/.js/.bsh/.py required).",
				Json.obj("code", p("string", "The complete code to write."), "mode", Json.obj("type", "string", "enum", Json.arr("replace", "insert", "append", "new")), "title", p("string", "Title for a new editor, e.g. 'Count_Nuclei.ijm'.")), "code", "mode"));
		t.add(fn("run_code", "Executes a macro, script or Java code in SWTImageJ and returns its return value, compiler/runtime errors, new Log window output and newly opened images. The user may be asked to approve.",
				Json.obj("code", p("string", "Code to run. For java: a complete class (implements PlugIn or PlugInFilter, or has main) or just statements, which are wrapped into PlugIn.run() with imports ij.*, ij.gui.*, ij.process.*, ij.measure.*, ij.plugin.*, ij.plugin.filter.*, ij.plugin.frame.*, ij.io.*, ij.text.*, java.util.*, java.io.*."),
						"language", Json.obj("type", "string", "enum", Json.arr("ijm", "java", "js", "bsh", "py"), "description", "ijm = ImageJ macro language (default), java = compiled in memory with javac")), "code"));
		t.add(fn("save_java_plugin", "Compiles a complete Java plugin class and installs it: saves Name.java and the .class files into the SWTImageJ plugins folder (subfolder, default LLM_Plugins; package classes into their package folder) and refreshes the menus. Class names with an underscore (e.g. Count_Cells) appear in the Plugins menu. The user may be asked to approve.",
				Json.obj("code", p("string", "Complete Java source of the plugin class."), "folder", p("string", "Subfolder of the plugins folder, default 'LLM_Plugins'.")), "code"));
		t.add(fn("run_editor", "Runs the complete content of the target editor (language from its title) and returns the result like run_code.", null));
		t.add(fn("search_commands", "Searches the SWTImageJ menu commands (names usable in run(\"...\") macro calls). Use it to verify exact command names before writing macros.",
				Json.obj("query", p("string", "Case-insensitive substring, e.g. 'threshold' or 'analyze particles'.")), "query"));
		if(KnowledgeBase.get().chunkCount() > 0) {
			t.add(fn("search_documents", "Searches the user's reference documents (" + KnowledgeBase.get().summary() + ", including the ImageJ macro functions reference if enabled) and returns the best-matching excerpts. Use it to look up exact macro function signatures, documented procedures, lab protocols or project conventions before writing code.",
					Json.obj("query", p("string", "Keywords or a question, e.g. 'setAutoThreshold methods' or 'nuclei segmentation protocol'."), "max_results", p("integer", "Default 5.")), "query"));
			t.add(fn("list_documents", "Lists the reference documents available to search_documents.", null));
		}
		t.add(fn("list_scripts", "Lists macro/script files in the Script Explorer folders (ImageJ plugins and macros directories), optionally filtered by a name substring. Paths are relative to the folder root shown in brackets.",
				Json.obj("query", p("string", "Optional case-insensitive filter on the relative path."))));
		t.add(fn("read_script", "Returns the content of a macro/script file from the Script Explorer folders without opening it, e.g. to reuse existing code.",
				Json.obj("path", p("string", "Path as returned by list_scripts, e.g. 'macros/StartupMacros.txt'.")), "path"));
		t.add(fn("open_script", "Opens a macro/script file from the Script Explorer folders in a Script Explorer tab and makes it the target editor.",
				Json.obj("path", p("string", "Path as returned by list_scripts.")), "path"));
		t.add(fn("get_log", "Returns the end of the ImageJ Log window.", Json.obj("max_chars", p("integer", "Maximum characters (default 4000)."))));
		t.add(fn("get_results_table", "Returns the ImageJ 'Results' table (or another table by title) as tab separated text.",
				Json.obj("title", p("string", "Table title, default 'Results'."), "max_rows", p("integer", "Default 50."))));
		return t;
	}

	/* ================================================================ dispatch */

	public String execute(String name, Map<String, Object> args) {
		try {
			switch(name) {
				case "get_imagej_context":
					return contextSummary();
				case "get_active_image_info":
					return activeImageInfo(true);
				case "get_editor_text":
					return editorText(Boolean.parseBoolean(Json.str(args, "line_numbers", "false")));
				case "mark_issues":
					return markIssues(args);
				case "check_code":
					return checkCode();
				case "apply_quick_fixes": {
					Editor ed = editors.target();
					if(ed == null)
						return "ERROR: no editor is open.";
					int n = EditorMarkers.pendingFixes(ed);
					if(n == 0)
						return "No pending quick fixes from the assistant in " + editors.labelOf(ed) + ".";
					if(settings.confirmEditorReplace && !host.confirm("Apply quick fixes", "The assistant wants to apply " + n + " quick fix(es) in " + editors.labelOf(ed) + ".\n\nAllow?"))
						return "DENIED: the user did not allow applying the fixes; they can double-click single markers instead.";
					String r = EditorMarkers.applyAll(ed);
					host.info(r.split("\n", 2)[0]);
					return r;
				}
				case "clear_markers": {
					Editor ed = editors.target();
					if(ed == null)
						return "ERROR: no editor is open.";
					EditorMarkers.clear(ed);
					return "OK: markers removed from " + editors.labelOf(ed) + ".";
				}
				case "set_editor_text":
					return setEditorText(Json.str(args, "code", ""), Json.str(args, "mode", "replace"), Json.str(args, "title", null));
				case "run_code":
					return runWithApproval(Json.str(args, "code", ""), Json.str(args, "language", "ijm"), "The assistant wants to run this code");
				case "run_editor": {
					Editor ed = editors.target();
					if(ed == null)
						return "ERROR: no editor is open.";
					return runWithApproval(editors.getText(ed), EditorBridge.languageOf(ed), "The assistant wants to run the editor '" + EditorBridge.titleOf(ed) + "'", ed);
				}
				case "save_java_plugin": {
					String code = Json.str(args, "code", "");
					String cls = JavaRunner.className(code);
					if(settings.confirmRun && !host.confirm("Install Java plugin", "The assistant wants to compile and install the Java plugin " + (cls == null ? "?" : cls) + " into the SWTImageJ plugins folder" + (Json.str(args, "folder", null) != null ? " (" + Json.str(args, "folder", "") + ")" : "") + ".\n\nAllow?"))
						return "DENIED: the user did not allow installing the plugin.";
					host.info("Compiling and installing Java plugin " + cls + " ...");
					return JavaRunner.install(code, Json.str(args, "folder", null));
				}
				case "search_commands":
					return searchCommands(Json.str(args, "query", ""));
				case "view_image": {
					ImagePlus imp = imageByTitle(Json.str(args, "title", null));
					if(imp == null)
						return "ERROR: no such image. Open images: " + String.join(" | ", WindowManager.getImageTitles());
					Snapshot sn = snapshot(imp, intArg(args, "max_size", settings.snapshotMaxSize), Boolean.parseBoolean(Json.str(args, "crop_to_roi", "false")));
					pendingSnapshots.add(sn);
					host.info("Sending snapshot " + sn.describe());
					return "OK: snapshot " + sn.describe() + " is attached as an image in the next message.";
				}
				case "open_image":
					return openImage(Json.str(args, "source", ""));
				case "list_sample_images":
					return "Sample images: " + String.join(" | ", sampleNames());
				case "select_image": {
					ImagePlus imp = imageByTitle(Json.str(args, "title", ""));
					if(imp == null)
						return "ERROR: no image titled '" + Json.str(args, "title", "") + "'. Open images: " + String.join(" | ", WindowManager.getImageTitles());
					IJ.selectWindow(imp.getID());
					return "OK: '" + imp.getTitle() + "' is now the active image.";
				}
				case "search_documents": {
					String q = Json.str(args, "query", "");
					String r = KnowledgeBase.get().excerpts(q, Math.max(1, Math.min(10, intArg(args, "max_results", 5))), 12000, null);
					host.info("Searched documents for \"" + (q.length() > 50 ? q.substring(0, 50) + "..." : q) + "\"");
					return r.isEmpty() ? "No matching excerpts for '" + q + "'. Try other keywords." : r;
				}
				case "list_documents": {
					StringBuilder sb = new StringBuilder();
					for(KnowledgeBase.DocInfo d : KnowledgeBase.get().documents())
						sb.append("- ").append(d.name).append(" (").append(d.type).append(", ").append(d.chunks).append(" excerpts").append("OK".equals(d.status) ? "" : ", " + d.status).append(")\n");
					return sb.length() == 0 ? "No documents." : sb.toString();
				}
				case "list_scripts":
					return listScripts(Json.str(args, "query", ""));
				case "read_script":
					return readScript(Json.str(args, "path", ""));
				case "open_script":
					return openScript(Json.str(args, "path", ""));
				case "get_log":
					return log(intArg(args, "max_chars", 4000));
				case "get_results_table":
					return resultsTable(Json.str(args, "title", "Results"), intArg(args, "max_rows", 50));
				default:
					return "ERROR: unknown tool " + name;
			}
		} catch(Throwable t) {
			return "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
		}
	}

	private static int intArg(Map<String, Object> a, String k, int def) {
		Object v = a == null ? null : a.get(k);
		if(v instanceof Number)
			return ((Number)v).intValue();
		try {
			return v == null ? def : Integer.parseInt(v.toString());
		} catch(NumberFormatException e) {
			return def;
		}
	}

	private static String clip(String s, int max) {
		if(s == null)
			return "";
		return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated " + (s.length() - max) + " chars]";
	}

	/* ================================================================ context */

	public String contextSummary() {
		StringBuilder sb = new StringBuilder();
		sb.append("SWTImageJ ").append(IJ.getFullVersion()).append(", Java ").append(System.getProperty("java.version")).append(", OS ").append(System.getProperty("os.name")).append('\n');
		String[] titles = WindowManager.getImageTitles();
		sb.append("Open images (").append(titles.length).append("): ");
		sb.append(titles.length == 0 ? "none" : String.join(" | ", titles)).append('\n');
		sb.append(activeImageInfo(false));
		List<Editor> eds = editors.listEditors();
		Editor target = editors.target();
		boolean explorerOpen = EditorBridge.findExplorer() != null;
		sb.append("Script Explorer: ").append(!EditorBridge.explorerAvailable() ? "not available in this version" : explorerOpen ? "open" : "closed").append(editors.useExplorer() ? " (default place for code)" : "").append('\n');
		sb.append("Open editors (").append(eds.size()).append("): ");
		for(Editor e : eds)
			sb.append(editors.labelOf(e)).append(e == target ? " [target]" : "").append(" | ");
		sb.append('\n');
		ResultsTable rt = ResultsTable.getResultsTable();
		if(rt != null && rt.size() > 0)
			sb.append("Results table: ").append(rt.size()).append(" rows, columns: ").append(rt.getColumnHeadings().trim().replace('\t', ',')).append('\n');
		return sb.toString();
	}

	public String activeImageInfo(boolean detailed) {
		ImagePlus imp = WindowManager.getCurrentImage();
		if(imp == null)
			return "Active image: none\n";
		StringBuilder sb = new StringBuilder();
		sb.append("Active image: \"").append(imp.getTitle()).append("\" id=").append(imp.getID()).append(", ").append(imp.getWidth()).append("x").append(imp.getHeight()).append(" px, ").append(imp.getBitDepth()).append("-bit");
		sb.append(", channels=").append(imp.getNChannels()).append(", slices=").append(imp.getNSlices()).append(", frames=").append(imp.getNFrames());
		sb.append(", current stack position=").append(imp.getCurrentSlice());
		if(imp.isComposite())
			sb.append(", composite");
		sb.append('\n');
		Calibration cal = imp.getCalibration();
		if(cal != null && cal.scaled())
			sb.append("Calibration: pixel ").append(cal.pixelWidth).append(" x ").append(cal.pixelHeight).append(" x ").append(cal.pixelDepth).append(' ').append(cal.getUnits()).append(cal.frameInterval > 0 ? ", frame interval " + cal.frameInterval : "").append('\n');
		else
			sb.append("Calibration: none (pixels)\n");
		Roi roi = imp.getRoi();
		if(roi != null) {
			Rectangle r = roi.getBounds();
			sb.append("ROI: ").append(roi.getTypeAsString()).append(roi.getName() != null ? " \"" + roi.getName() + "\"" : "").append(" bounds x=").append(r.x).append(" y=").append(r.y).append(" w=").append(r.width).append(" h=").append(r.height).append('\n');
		} else {
			sb.append("ROI: none\n");
		}
		if(detailed) {
			try {
				ImageStatistics st = imp.getStatistics();
				sb.append(String.format("Statistics (current plane%s): mean=%.4g, std=%.4g, min=%.4g, max=%.4g%n", roi != null ? ", inside ROI" : "", st.mean, st.stdDev, st.min, st.max));
			} catch(Throwable t) {
				sb.append("Statistics: unavailable (").append(t.getMessage()).append(")\n");
			}
			String info = imp.getInfoProperty();
			if(info != null && !info.isBlank())
				sb.append("Image info (truncated):\n").append(clip(info, 3000)).append('\n');
		}
		return sb.toString();
	}

	private String editorText(boolean lineNumbers) {
		Editor ed = editors.target();
		if(ed == null)
			return "No editor is open. Use set_editor_text with mode 'new' to create one, or open_script to open an existing file.";
		String sel = editors.getSelection(ed);
		String text = editors.getText(ed);
		if(lineNumbers)
			text = numbered(text);
		return "Editor: " + editors.labelOf(ed) + " (language: " + EditorBridge.languageOf(ed) + ")\n" + (sel.isEmpty() ? "" : "Selected text:\n" + clip(sel, 4000) + "\n") + (EditorMarkers.available() ? EditorMarkers.describe(ed) : "") + "----- editor content" + (lineNumbers ? " (with line numbers)" : "") + " -----\n" + clip(text, 40000);
	}

	private static String numbered(String text) {
		String[] lines = text.split("\n", -1);
		StringBuilder sb = new StringBuilder();
		int w = String.valueOf(lines.length).length();
		for(int i = 0; i < lines.length; i++)
			sb.append(String.format("%" + w + "d| ", i + 1)).append(lines[i]).append('\n');
		return sb.toString();
	}

	/* ================================================================ markers and quick fixes */

	private String markIssues(Map<String, Object> args) {
		if(!EditorMarkers.available())
			return EditorMarkers.UNAVAILABLE;
		Editor ed = editors.target();
		if(ed == null)
			return "ERROR: no editor is open.";
		List<Object> raw = Json.asList(args.get("issues"));
		if(raw == null || raw.isEmpty())
			return "ERROR: 'issues' must be a non-empty array.";
		List<EditorMarkers.Issue> issues = new ArrayList<>();
		for(Object o : raw) {
			Map<String, Object> m = Json.asMap(o);
			if(m == null)
				continue;
			EditorMarkers.Issue is = EditorMarkers.Issue.at(intArg(m, "line", 0), Json.str(m, "severity", "error"), Json.str(m, "message", "Problem"));
			is.endLine = intArg(m, "end_line", is.line);
			is.match = Json.str(m, "match", null);
			is.occurrence = intArg(m, "occurrence", 1);
			is.fixDescription = Json.str(m, "fix_description", null);
			is.replacement = m.containsKey("replacement") && m.get("replacement") != null ? m.get("replacement").toString() : null;
			issues.add(is);
		}
		boolean clear = !"false".equalsIgnoreCase(Json.str(args, "clear_previous", "true"));
		String r = EditorMarkers.mark(ed, issues, clear);
		host.info(r.split("\n", 2)[0]);
		host.markersChanged();
		return r;
	}

	private String checkCode() {
		if(!EditorMarkers.available())
			return EditorMarkers.UNAVAILABLE;
		Editor ed = editors.target();
		if(ed == null)
			return "ERROR: no editor is open.";
		String lang = EditorBridge.languageOf(ed);
		String code = editors.getText(ed);
		if(!"java".equals(lang))
			return "check_code can only compile Java. This editor contains " + languageName(lang) + ": review the code yourself (get_editor_text with line_numbers=true) and mark problems with mark_issues, or use run_editor, which marks runtime errors.";
		JavaRunner.Compiled c = JavaRunner.compile(code);
		EditorMarkers.clear(ed);
		if(c.issues.isEmpty())
			return c.ok ? "OK: the Java code compiles without errors or warnings. Markers cleared." : "Compilation failed without line information:\n" + c.diagnostics;
		EditorMarkers.mark(ed, c.issues, false);
		host.markersChanged();
		String[] lines = code.split("\n", -1);
		StringBuilder sb = new StringBuilder(c.ok ? "Compiles, with warnings" : "Compilation FAILED").append(" - ").append(c.issues.size()).append(" problem(s), marked in ").append(editors.labelOf(ed)).append(":\n");
		for(EditorMarkers.Issue is : c.issues) {
			sb.append("line ").append(is.line).append(is.column > 0 ? ", column " + is.column : "").append(' ').append(is.severity).append(": ").append(is.message).append('\n');
			if(is.line >= 1 && is.line <= lines.length)
				sb.append("    ").append(lines[is.line - 1].strip()).append('\n');
		}
		sb.append("Add quick fixes with mark_issues (clear_previous=true replaces these markers).");
		host.info("Check: " + c.issues.size() + " problem(s) marked");
		return sb.toString();
	}

	private String setEditorText(String code, String mode, String title) {
		if(code == null || code.isEmpty())
			return "ERROR: empty code.";
		Editor ed = editors.target();
		if("new".equals(mode) || ed == null) {
			String t = title == null || title.isBlank() ? "LLM_Macro" + EditorBridge.extensionFor(settings.defaultLanguage) : title;
			if(t.indexOf('.') < 0)
				t += EditorBridge.extensionFor(settings.defaultLanguage);
			// a Java class must be saved as <ClassName>.java to compile
			String cls = JavaRunner.isCompleteClass(code) ? JavaRunner.className(code) : null;
			if(cls != null && (t.endsWith(".java") || "java".equals(settings.defaultLanguage) && t.startsWith("LLM_Macro")))
				t = cls + ".java";
			Editor n = editors.openNew(t, code);
			String where = editors.isInExplorer(n) ? "Script Explorer tab" : "editor window";
			host.info("Opened new " + where + " \"" + t + "\"");
			return "OK: new " + where + " '" + t + "' opened with " + code.split("\n").length + " lines (not saved yet).";
		}
		switch(mode) {
			case "insert":
				editors.insertAtCaret(ed, code);
				host.info("Inserted code into \"" + EditorBridge.titleOf(ed) + "\"");
				return "OK: inserted into '" + EditorBridge.titleOf(ed) + "'.";
			case "append":
				editors.append(ed, code);
				host.info("Appended code to \"" + EditorBridge.titleOf(ed) + "\"");
				return "OK: appended to '" + EditorBridge.titleOf(ed) + "'.";
			default:
				if(settings.confirmEditorReplace && !host.confirm("Replace editor content", "The assistant wants to replace the whole content of \"" + EditorBridge.titleOf(ed) + "\".\n\nAllow?"))
					return "DENIED: the user did not allow replacing the editor content. Offer the code in the chat instead.";
				editors.replaceAll(ed, code);
				host.info("Replaced content of \"" + EditorBridge.titleOf(ed) + "\"");
				return "OK: content of '" + EditorBridge.titleOf(ed) + "' replaced.";
		}
	}

	private String searchCommands(String query) {
		Hashtable<?, ?> cmds = Menus.getCommands();
		if(cmds == null)
			return "Command table not available.";
		String q = query.toLowerCase().trim();
		List<String> hits = new ArrayList<>();
		Enumeration<?> keys = cmds.keys();
		while(keys.hasMoreElements()) {
			String k = String.valueOf(keys.nextElement());
			if(q.isEmpty() || k.toLowerCase().contains(q))
				hits.add(k);
		}
		java.util.Collections.sort(hits);
		if(hits.isEmpty())
			return "No command contains '" + query + "'. Try a shorter or different word.";
		return hits.size() + " commands: " + String.join(" | ", hits.subList(0, Math.min(80, hits.size())));
	}

	private static String log(int max) {
		String l = IJ.getLog();
		if(l == null || l.isEmpty())
			return "(Log window is empty)";
		return l.length() <= max ? l : "...\n" + l.substring(l.length() - max);
	}

	private static String resultsTable(String title, int maxRows) {
		ResultsTable rt = title == null || title.equals("Results") ? ResultsTable.getResultsTable() : ResultsTable.getResultsTable(title);
		if(rt == null || rt.size() == 0)
			return "Table '" + title + "' is empty or not open.";
		StringBuilder sb = new StringBuilder(rt.getColumnHeadings()).append('\n');
		int n = Math.min(maxRows, rt.size());
		for(int i = 0; i < n; i++)
			sb.append(rt.getRowAsString(i)).append('\n');
		if(n < rt.size())
			sb.append("... ").append(rt.size() - n).append(" more rows\n");
		return clip(sb.toString(), MAX_RESULT);
	}

	/* ================================================================ images */

	/** Takes and clears the snapshots requested by the model. */
	public List<Snapshot> drainSnapshots() {
		synchronized(pendingSnapshots) {
			List<Snapshot> l = new ArrayList<>(pendingSnapshots);
			pendingSnapshots.clear();
			return l;
		}
	}

	private static ImagePlus imageByTitle(String title) {
		if(title == null || title.isBlank())
			return WindowManager.getCurrentImage();
		return WindowManager.getImage(title.trim());
	}

	/**
	 * Renders the image as it is displayed (LUT, display range, overlay, current
	 * stack position; composites merged) via ImagePlus.flatten(), optionally
	 * cropped to the selection and scaled so the longest side is at most maxSize,
	 * and encodes it as a PNG data URL.
	 */
	public static Snapshot snapshot(ImagePlus imp, int maxSize, boolean cropToRoi) throws java.io.IOException {
		ImageProcessor ip;
		try {
			ip = imp.flatten().getProcessor();
		} catch(Throwable t) {
			ip = imp.getProcessor().duplicate().convertToRGB(); // fallback: current plane without overlay
		}
		Snapshot sn = new Snapshot();
		sn.title = imp.getTitle();
		Roi roi = imp.getRoi();
		if(cropToRoi && roi != null) {
			Rectangle r = roi.getBounds().intersection(new Rectangle(0, 0, ip.getWidth(), ip.getHeight()));
			if(r.width > 0 && r.height > 0) {
				ip.setRoi(r);
				ip = ip.crop();
				sn.note = "cropped to selection at x=" + r.x + " y=" + r.y;
			}
		}
		if(imp.getStackSize() > 1)
			sn.note = (sn.note == null ? "" : sn.note + ", ") + "stack position c=" + imp.getC() + " z=" + imp.getZ() + " t=" + imp.getT() + " of " + imp.getNChannels() + "/" + imp.getNSlices() + "/" + imp.getNFrames();
		sn.originalWidth = ip.getWidth();
		sn.originalHeight = ip.getHeight();
		int max = Math.max(64, maxSize);
		int longest = Math.max(ip.getWidth(), ip.getHeight());
		if(longest > max) {
			double f = (double)max / longest;
			ip.setInterpolationMethod(ImageProcessor.BILINEAR);
			ip = ip.resize(Math.max(1, (int)Math.round(ip.getWidth() * f)), Math.max(1, (int)Math.round(ip.getHeight() * f)), true);
		}
		sn.width = ip.getWidth();
		sn.height = ip.getHeight();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(ip.getBufferedImage(), "png", out);
		sn.dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
		return sn;
	}

	/** Names of the File > Open Samples entries (commands handled by ij.plugin.URLOpener with an argument). */
	private static List<String> sampleNames() {
		List<String> names = new ArrayList<>();
		Hashtable<?, ?> cmds = Menus.getCommands();
		if(cmds != null)
			for(Map.Entry<?, ?> e : cmds.entrySet())
				if(String.valueOf(e.getValue()).startsWith("ij.plugin.URLOpener(\""))
					names.add(String.valueOf(e.getKey()));
		java.util.Collections.sort(names);
		return names;
	}

	private String openImage(String source) {
		String src = source == null ? "" : source.trim();
		if(src.isEmpty())
			return "ERROR: no source given.";
		Set<Integer> before = ids();
		String how;
		for(String sample : sampleNames()) {
			if(sample.equalsIgnoreCase(src) || sample.replaceAll("\\s*\\(.*\\)$", "").equalsIgnoreCase(src)) {
				host.info("Opening sample image \"" + sample + "\" ...");
				IJ.run(sample);
				src = null;
				break;
			}
		}
		if(src == null) {
			how = "sample";
		} else {
			File f = new File(src.startsWith("~") ? System.getProperty("user.home") + src.substring(1) : src);
			boolean url = src.matches("(?i)^https?://.*");
			if(!url && !f.isFile())
				return "ERROR: file not found: " + f.getAbsolutePath() + ". Use an absolute path, an http(s) URL or a sample name (list_sample_images).";
			host.info("Opening " + (url ? src : f.getAbsolutePath()) + " ...");
			ImagePlus imp = IJ.openImage(url ? src : f.getAbsolutePath());
			if(imp == null)
				return "ERROR: SWTImageJ could not open '" + src + "' as an image." + errorSuffix();
			imp.show();
			how = url ? "URL" : "file";
		}
		IJ.wait(200); // let the window register
		Set<Integer> after = ids();
		after.removeAll(before);
		if(after.isEmpty())
			return "Opened " + how + ", but no new image window appeared." + errorSuffix();
		StringBuilder sb = new StringBuilder("OK: opened ");
		for(Integer id : after) {
			ImagePlus imp = WindowManager.getImage(id);
			if(imp != null)
				sb.append('"').append(imp.getTitle()).append("\" ").append(imp.getWidth()).append("x").append(imp.getHeight()).append(" ").append(imp.getBitDepth()).append("-bit, ").append(imp.getStackSize()).append(" plane(s); ");
		}
		return sb.append("it is now the active image.").toString();
	}

	private static String errorSuffix() {
		String e = IJ.getErrorMessage();
		return e == null ? "" : " ImageJ error: " + e;
	}

	/* ================================================================ Script Explorer files */

	private static final String[] SCRIPT_EXT = {".ijm", ".txt", ".js", ".bsh", ".py", ".java", ".groovy"};
	private static final int MAX_LISTED = 300;

	/** The Script Explorer roots: ImageJ plugins and macros directories. */
	private static List<File> roots() {
		List<File> r = new ArrayList<>();
		for(String p : new String[]{Menus.getPlugInsPath(), Menus.getMacrosPath()}) {
			if(p != null) {
				File f = new File(p);
				if(f.isDirectory() && !r.contains(f))
					r.add(f);
			}
		}
		return r;
	}

	private static boolean isScript(File f) {
		String n = f.getName().toLowerCase();
		for(String e : SCRIPT_EXT)
			if(n.endsWith(e))
				return true;
		return false;
	}

	private static void collect(File dir, File root, List<String> out, String q, int depth) {
		File[] files = dir.listFiles();
		if(files == null || depth > 6)
			return;
		java.util.Arrays.sort(files);
		for(File f : files) {
			if(out.size() >= MAX_LISTED)
				return;
			if(f.isDirectory())
				collect(f, root, out, q, depth + 1);
			else if(isScript(f)) {
				String rel = root.getName() + "/" + root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
				if(q.isEmpty() || rel.toLowerCase().contains(q))
					out.add(rel + " (" + f.length() + " bytes)");
			}
		}
	}

	private String listScripts(String query) {
		List<File> roots = roots();
		if(roots.isEmpty())
			return "No plugins/macros folder found.";
		List<String> out = new ArrayList<>();
		for(File r : roots)
			collect(r, r, out, query.toLowerCase().trim(), 0);
		StringBuilder sb = new StringBuilder("Roots: ");
		for(File r : roots)
			sb.append('[').append(r.getName()).append("] = ").append(r.getAbsolutePath()).append("  ");
		sb.append('\n').append(out.isEmpty() ? "No matching script files." : String.join("\n", out));
		if(out.size() >= MAX_LISTED)
			sb.append("\n... (list truncated, use a query)");
		return sb.toString();
	}

	/** Resolves a list_scripts path; only files inside the Script Explorer roots are allowed. */
	private static File resolveScript(String path) throws IOException {
		String p = path.replaceAll("\\s+\\(\\d+ bytes\\)$", "").replace('\\', '/').trim();
		for(File root : roots()) {
			File candidate;
			if(new File(p).isAbsolute())
				candidate = new File(p);
			else if(p.startsWith(root.getName() + "/"))
				candidate = new File(root, p.substring(root.getName().length() + 1));
			else
				candidate = new File(root, p);
			File c = candidate.getCanonicalFile();
			if(c.getPath().startsWith(root.getCanonicalPath() + File.separator) && c.isFile())
				return c;
		}
		throw new IOException("File not found in the Script Explorer folders: " + path + " (use list_scripts)");
	}

	private String readScript(String path) throws IOException {
		File f = resolveScript(path);
		String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		return "File: " + f.getAbsolutePath() + "\n----- content -----\n" + clip(text, 40000);
	}

	private String openScript(String path) throws IOException {
		File f = resolveScript(path);
		Editor ed = editors.useExplorer() ? editors.openFileInExplorer(f) : null;
		if(ed == null) {
			ed = new Editor(f.getName());
			ed.open(f.getParent() + File.separator, f.getName());
		}
		editors.setSelected(ed);
		host.info("Opened \"" + f.getName() + "\"" + (editors.isInExplorer(ed) ? " in the Script Explorer" : ""));
		return "OK: '" + f.getName() + "' opened and is now the target editor (" + editors.labelOf(ed) + ").";
	}

	/* ================================================================ running code */

	private String runWithApproval(String code, String language, String question) {
		return runWithApproval(code, language, question, null);
	}

	/** markIn != null: errors are shown as markers in this editor (fresh pass: old markers are cleared). */
	private String runWithApproval(String code, String language, String question, Editor markIn) {
		if(code == null || code.isBlank())
			return "ERROR: nothing to run.";
		if(settings.confirmRun) {
			String preview = code.length() > 1200 ? code.substring(0, 1200) + "\n..." : code;
			if(!host.confirm("Run " + languageName(language), question + " (" + languageName(language) + "):\n\n" + preview + "\n\nRun it now?"))
				return "DENIED: the user did not allow running this code.";
		}
		host.info("Running " + languageName(language) + " ...");
		if(markIn == null || !settings.markErrors || !EditorMarkers.available())
			return runCode(code, language);
		List<EditorMarkers.Issue> issues = new ArrayList<>();
		String report = runCode(code, language, issues);
		return report + markReport(markIn, issues);
	}

	/** Replaces the editor's markers with the given issues; returns a note for the model. */
	// (called only when EditorMarkers.available())
	private String markReport(Editor ed, List<EditorMarkers.Issue> issues) {
		EditorMarkers.clear(ed);
		if(issues.isEmpty())
			return "";
		String r = EditorMarkers.mark(ed, issues, false);
		host.info("Marked " + issues.size() + " problem(s) in " + editors.labelOf(ed));
		return "\nEditor markers: " + r.split("\n", 2)[0] + " Use mark_issues to add quick fixes for them.";
	}

	public static String languageName(String lang) {
		switch(lang == null ? "ijm" : lang) {
			case "js":
				return "JavaScript";
			case "bsh":
				return "BeanShell";
			case "py":
				return "Python (Jython)";
			case "java":
				return "Java";
			default:
				return "ImageJ macro";
		}
	}

	/**
	 * Runs code on the calling thread (never call on the SWT display thread) and
	 * returns a report with return value, errors, log output and new images.
	 */
	public static String runCode(String code, String language) {
		return runCode(code, language, null);
	}

	/** As above; errors with a known line (compiler, macro interpreter, Java exception) are added to issues. */
	public static String runCode(String code, String language, List<EditorMarkers.Issue> issues) {
		String logBefore = IJ.getLog();
		Set<Integer> idsBefore = ids();
		long t0 = System.currentTimeMillis();
		String value = null, error = null;
		StringBuilder notes = new StringBuilder();
		try {
			switch(language == null ? "ijm" : language) {
				case "java":
					error = JavaRunner.compileAndRun(code, notes, issues);
					break;
				case "js":
					value = new Macro_Runner().runJavaScript(code, "");
					break;
				case "bsh":
					value = Macro_Runner.runBeanShell(code, "");
					break;
				case "py":
					value = Macro_Runner.runPython(code, "");
					break;
				case "ijm":
				case "macro":
				case "": {
					Interpreter interp = new Interpreter();
					interp.setIgnoreErrors(true); // report errors back instead of opening a dialog
					interp.run(code);
					error = interp.getErrorMessage();
					if(error != null && issues != null) {
						try {
							int line = interp.getLineNumber();
							if(line > 0)
								issues.add(EditorMarkers.Issue.at(line, "error", error.split("\n", 2)[0]));
						} catch(Throwable ignored) {
							// no line information available
						}
					}
					break;
				}
				default: {
					ScriptEngine eng = new ScriptEngineManager().getEngineByName(language);
					if(eng == null)
						error = "No script engine for '" + language + "' on the class path.";
					else {
						eng.put("IJ", IJ.class);
						eng.put("imp", WindowManager.getCurrentImage());
						Object r = eng.eval(code);
						value = r == null ? null : r.toString();
					}
				}
			}
		} catch(Throwable t) {
			error = t.getClass().getSimpleName() + ": " + t.getMessage();
		}
		String ijErr = IJ.getErrorMessage();
		StringBuilder sb = new StringBuilder();
		sb.append(error == null ? "Finished" : "FAILED").append(" in ").append(System.currentTimeMillis() - t0).append(" ms.\n");
		if(error != null)
			sb.append("Error: ").append(error).append('\n');
		if(ijErr != null)
			sb.append("IJ.error message: ").append(ijErr).append('\n');
		if(notes.length() > 0)
			sb.append(notes);
		if(value != null && !value.isEmpty() && !"null".equals(value))
			sb.append("Return value: ").append(clip(value, 2000)).append('\n');
		String logAfter = IJ.getLog();
		if(logAfter != null && !logAfter.equals(logBefore)) {
			String delta = logBefore != null && logAfter.startsWith(logBefore) ? logAfter.substring(logBefore.length()) : logAfter;
			sb.append("New Log output:\n").append(clip(delta.strip(), 4000)).append('\n');
		}
		Set<Integer> idsAfter = ids();
		idsAfter.removeAll(idsBefore);
		for(Integer id : idsAfter) {
			ImagePlus imp = WindowManager.getImage(id);
			if(imp != null)
				sb.append("New image: \"").append(imp.getTitle()).append("\" ").append(imp.getWidth()).append("x").append(imp.getHeight()).append(" ").append(imp.getBitDepth()).append("-bit\n");
		}
		ResultsTable rt = ResultsTable.getResultsTable();
		if(rt != null && rt.size() > 0)
			sb.append("Results table now has ").append(rt.size()).append(" rows.\n");
		return clip(sb.toString(), MAX_RESULT);
	}

	private static Set<Integer> ids() {
		Set<Integer> s = new HashSet<>();
		int[] l = WindowManager.getIDList();
		if(l != null)
			for(int i : l)
				s.add(i);
		return s;
	}
}
