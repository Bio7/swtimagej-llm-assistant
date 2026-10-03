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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import ij.plugin.frame.Editor;

/**
 * Error/warning/info markers with optional quick fixes in the SWTImageJ editor
 * (Editor.addMarker / Editor.QuickFix / applyQuickFix, recent SWTImageJ).
 * Markers are addressed by 1-based line and optionally by an exact text on
 * that line; the plugin remembers the markers it placed so their fixes can be
 * listed and applied together.
 */
public final class EditorMarkers {

	/** One issue to mark (from the compiler, the macro interpreter or the model). */
	public static final class Issue {

		public int line;              // 1-based
		public int endLine;           // 1-based, >= line; whole lines line..endLine when match is null
		public int column = -1;       // 1-based start column (compiler), or -1
		public int length = -1;       // characters from column, or -1
		public String match;          // exact text on the line to mark (optional)
		public int occurrence = 1;    // which occurrence of match on the line
		public String severity = "error";
		public String message = "";
		public String fixDescription; // optional
		public String replacement;    // optional: replaces the marked range

		public static Issue at(int line, String severity, String message) {
			Issue i = new Issue();
			i.line = line;
			i.endLine = line;
			i.severity = severity;
			i.message = message;
			return i;
		}
	}

	/** A marker placed by the plugin. */
	private static final class Placed {

		int start, end;
		Issue issue;
		Object fix;
	}

	private static Boolean available;
	private static String unavailableReason = "";
	private static final Map<Editor, List<Placed>> placed = Collections.synchronizedMap(new WeakHashMap<>());

	private EditorMarkers() {
	}

	/** True if this SWTImageJ version has the marker / quick fix API. */
	public static synchronized boolean available() {
		if(available == null) {
			try {
				Class<?> qf = Class.forName("ij.plugin.frame.Editor$QuickFix");
				Editor.class.getMethod("addMarker", int.class, int.class, int.class, String.class, qf);
				Editor.class.getMethod("applyQuickFix", int.class);
				Editor.class.getMethod("getQuickFixAt", int.class);
				Editor.class.getMethod("clearMarkers");
				available = true;
			} catch(Throwable t) {
				available = false;
				unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
			}
		}
		return available;
	}

	/** Why the API is missing (e.g. the missing method), empty if available. */
	public static String unavailableReason() {
		available();
		return unavailableReason;
	}

	/**
	 * Places an info marker with a no-op quick fix on the first non-empty line, so
	 * the user can verify that markers are displayed (double-click removes it).
	 */
	public static String selfTest(Editor ed) {
		if(!available())
			return UNAVAILABLE + " (" + unavailableReason + ")";
		String text = ed.getText();
		String[] lines = text == null ? new String[0] : text.split("\n", -1);
		int n = 0;
		while(n < lines.length && lines[n].strip().isEmpty())
			n++;
		if(n >= lines.length)
			return "The editor is empty - type a line and try again.";
		String line = lines[n].replace("\r", "");
		Issue is = Issue.at(n + 1, "info", "LLM Assistant marker test: markers work. Double-click to remove this marker (the text stays unchanged).");
		is.match = line.strip();
		is.fixDescription = "Remove test marker";
		is.replacement = line.strip();
		return mark(ed, java.util.List.of(is), false);
	}

	public static final String UNAVAILABLE = "Editor markers are not available: this SWTImageJ version has no marker/quick fix API (update SWTImageJ).";

	/* ================================================================ placing */

	/** Start offsets of all lines of text (index 0 = line 1), plus a final entry = text length + 1. */
	private static int[] lineStarts(String text) {
		List<Integer> l = new ArrayList<>();
		l.add(0);
		for(int i = 0; i < text.length(); i++)
			if(text.charAt(i) == '\n')
				l.add(i + 1);
		l.add(text.length() + 1);
		int[] a = new int[l.size()];
		for(int i = 0; i < a.length; i++)
			a[i] = l.get(i);
		return a;
	}

	/** End of line n (1-based), excluding the line break. */
	private static int lineEnd(String text, int[] starts, int n) {
		int end = starts[n] - 1; // position of '\n' (or text length)
		if(end > 0 && end <= text.length() && text.charAt(end - 1) == '\r')
			end--;
		return Math.min(end, text.length());
	}

	/**
	 * Places the issues in the editor.
	 *
	 * @return a report line per issue ("OK line 3: ..." or "SKIPPED ...")
	 */
	public static String mark(Editor ed, List<Issue> issues, boolean clearFirst) {
		if(!available())
			return UNAVAILABLE;
		if(clearFirst)
			clear(ed);
		String text = ed.getText();
		if(text == null)
			return "ERROR: editor has no text.";
		int[] starts = lineStarts(text);
		int lines = starts.length - 1;
		StringBuilder sb = new StringBuilder();
		int ok = 0, fixes = 0;
		List<Placed> list = placed.computeIfAbsent(ed, _ -> Collections.synchronizedList(new ArrayList<>()));
		for(Issue is : issues) {
			if(is.line < 1 || is.line > lines) {
				sb.append("SKIPPED: line ").append(is.line).append(" does not exist (editor has ").append(lines).append(" lines): ").append(is.message).append('\n');
				continue;
			}
			int endLine = Math.max(is.line, Math.min(is.endLine <= 0 ? is.line : is.endLine, lines));
			int lineStart = starts[is.line - 1];
			int start, end;
			if(is.match != null && !is.match.isEmpty()) {
				String lineText = text.substring(lineStart, lineEnd(text, starts, is.line));
				int idx = -1;
				for(int k = 0; k < Math.max(1, is.occurrence); k++) {
					idx = lineText.indexOf(is.match, idx + 1);
					if(idx < 0)
						break;
				}
				if(idx < 0) {
					sb.append("SKIPPED: text \"").append(is.match).append("\" not found on line ").append(is.line).append(" (line is: ").append(lineText.strip()).append(")\n");
					continue;
				}
				start = lineStart + idx;
				end = start + is.match.length();
			} else if(is.column > 0) {
				int le = lineEnd(text, starts, is.line);
				start = Math.min(lineStart + is.column - 1, le);
				end = is.length > 0 ? Math.min(start + is.length, le) : le;
				if(end <= start) { // error at end of line: mark the whole line instead
					start = lineStart;
					end = le;
				}
			} else {
				start = lineStart;
				end = lineEnd(text, starts, endLine);
			}
			if(end <= start) // empty line: include the line break so the marker is visible
				end = Math.min(start + 1, text.length());
			if(end <= start) {
				sb.append("SKIPPED: empty range at line ").append(is.line).append('\n');
				continue;
			}
			try {
				Placed p = new Placed();
				p.start = start;
				p.end = end;
				p.issue = is;
				p.fix = MarkerSupport.add(ed, start, end, is.severity, is.message, is.fixDescription, is.replacement);
				list.add(p);
				ok++;
				if(p.fix != null)
					fixes++;
				sb.append("OK line ").append(is.line).append(is.match != null ? " \"" + is.match + "\"" : endLine > is.line ? "-" + endLine : "").append(": ").append(is.severity).append(" - ").append(is.message).append(p.fix != null ? " [quick fix]" : "").append('\n');
			} catch(Throwable t) {
				sb.append("FAILED line ").append(is.line).append(": ").append(t).append('\n');
			}
		}
		sb.insert(0, "Placed " + ok + " marker(s) in '" + EditorBridge.titleOf(ed) + "', " + fixes + " with quick fix (the user applies a fix by double-clicking the marked text).\n");
		return sb.toString().strip();
	}

	public static void clear(Editor ed) {
		if(!available() || ed == null)
			return;
		MarkerSupport.clear(ed);
		placed.remove(ed);
	}

	/** Number of quick fixes placed by the plugin that are still applicable. */
	public static int pendingFixes(Editor ed) {
		if(!available() || ed == null)
			return 0;
		List<Placed> list = placed.get(ed);
		if(list == null)
			return 0;
		int n = 0;
		synchronized(list) {
			for(Placed p : list)
				if(MarkerSupport.pending(ed, p.start, p.end, p.fix))
					n++;
		}
		return n;
	}

	/**
	 * Applies all quick fixes placed by the plugin, from the end of the text to
	 * the start, so earlier offsets stay valid. Fixes whose marker has moved or
	 * disappeared (e.g. text edited meanwhile) are skipped.
	 */
	public static String applyAll(Editor ed) {
		if(!available())
			return UNAVAILABLE;
		List<Placed> list = placed.get(ed);
		if(list == null || list.isEmpty())
			return "No quick fixes from the assistant in '" + EditorBridge.titleOf(ed) + "'.";
		List<Placed> copy;
		synchronized(list) {
			copy = new ArrayList<>(list);
		}
		copy.sort((a, b) -> Integer.compare(b.start, a.start));
		int applied = 0, skipped = 0;
		StringBuilder sb = new StringBuilder();
		for(Placed p : copy) {
			if(p.fix == null)
				continue;
			if(MarkerSupport.apply(ed, p.start, p.end, p.fix)) {
				applied++;
				list.remove(p);
				sb.append("applied line ").append(p.issue.line).append(": ").append(p.issue.fixDescription == null ? p.issue.message : p.issue.fixDescription).append('\n');
			} else {
				skipped++;
			}
		}
		return ("Applied " + applied + " quick fix(es)" + (skipped > 0 ? ", skipped " + skipped + " (already applied, or the text changed)" : "") + ".\n" + sb).strip();
	}

	/** Short list of the markers the plugin placed (for the model). */
	public static String describe(Editor ed) {
		List<Placed> list = placed.get(ed);
		if(list == null || list.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder("Markers placed by the assistant:\n");
		synchronized(list) {
			for(Placed p : list)
				sb.append("- line ").append(p.issue.line).append(' ').append(p.issue.severity).append(": ").append(p.issue.message).append(p.fix == null ? "" : MarkerSupport.pending(ed, p.start, p.end, p.fix) ? " [quick fix pending]" : " [fix applied or outdated]").append('\n');
		}
		return sb.toString();
	}
}
