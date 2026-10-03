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

import ij.plugin.frame.Editor;

/**
 * Direct calls into the Editor marker / quick fix API (recent SWTImageJ only).
 * This class is loaded only after {@link EditorMarkers#available()} confirmed the
 * API exists, so older SWTImageJ versions never link against it.
 */
final class MarkerSupport {

	private MarkerSupport() {
	}

	static int severity(String s) {
		if(s == null)
			return Editor.MARKER_ERROR;
		switch(s.toLowerCase()) {
			case "warning":
				return Editor.MARKER_WARNING;
			case "info":
				return Editor.MARKER_INFO;
			default:
				return Editor.MARKER_ERROR;
		}
	}

	/** Adds a marker; returns the QuickFix object (or null) so it can be identified later. */
	static Object add(Editor ed, int start, int end, String severity, String message, String fixDescription, String replacement) {
		Editor.QuickFix fix = replacement == null ? null : new Editor.QuickFix(fixDescription == null || fixDescription.isBlank() ? "Apply suggested fix" : fixDescription, replacement);
		ed.addMarker(start, end, severity(severity), message, fix);
		return fix;
	}

	static void clear(Editor ed) {
		ed.clearMarkers();
	}

	/**
	 * Offset inside [start, end) where the editor reports exactly this QuickFix
	 * (markers can overlap), or -1 if it is gone (applied, or the text changed).
	 */
	private static int find(Editor ed, int start, int end, Object fix) {
		if(fix == null)
			return -1;
		for(int o = start; o < Math.min(end, start + 500); o++)
			if(ed.getQuickFixAt(o) == fix)
				return o;
		return -1;
	}

	/** Applies the plugin's own fix if its marker is still present. */
	static boolean apply(Editor ed, int start, int end, Object fix) {
		int o = find(ed, start, end, fix);
		return o >= 0 && ed.applyQuickFix(o);
	}

	static boolean pending(Editor ed, int start, int end, Object fix) {
		return find(ed, start, end, fix) >= 0;
	}
}
