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
 * An editor the assistant reads and writes: an SWTImageJ editor (Script
 * Explorer tab or editor window, see {@link ImageJTarget}) or an editor of the
 * application that embeds SWTImageJ (see {@link ExternalTarget}). The methods can
 * be called from any thread. Two instances for the same editor are equal.
 */
abstract class CodeTarget {

	/** The title, usually the file name with extension. */
	abstract String title();

	/** The language: "ijm", "java", "js", "bsh", "py" or another file extension (e.g., "r"). */
	abstract String language();

	/** Where the editor lives, used as label prefix (e.g., "Explorer", "Window" or an application name). */
	abstract String location();

	abstract String text();

	abstract String selection();

	abstract void replaceAll(String text);

	/** Inserts at the caret, replacing the current selection if there is one. */
	abstract void insertAtCaret(String text);

	abstract void append(String text);

	abstract boolean isOpen();

	/** The SWTImageJ editor or null. */
	Editor imagejEditor() {
		return null;
	}

	/** The editor for markers and quick fixes, or null if the editor does not support them. */
	MarkerHost markerHost() {
		return null;
	}

	/** Maps a file extension or language name to the assistant's language ids. */
	static String normalizeLanguage(String lang) {
		String l = lang == null ? "" : lang.toLowerCase();
		switch(l) {
			case "":
			case "ijm":
			case "macro":
			case "txt":
				return "ijm";
			case "javascript":
				return "js";
			case "beanshell":
				return "bsh";
			case "python":
			case "jython":
				return "py";
			default:
				return l;
		}
	}
}
