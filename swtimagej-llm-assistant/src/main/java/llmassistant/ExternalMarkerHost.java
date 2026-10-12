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

import ij.swt.CodeEditor;
import ij.swt.CodeMarker;

/**
 * Markers in an editor of the embedding application (ij.swt.CodeEditor marker
 * API, recent SWTImageJ). Only created if {@link ExternalTarget#markersApiPresent()}
 * and the editor supports markers, so older SWTImageJ versions never link
 * against this class. The markers are kept separate from the editor's own
 * markers.
 */
final class ExternalMarkerHost extends MarkerHost {

	private final CodeEditor editor;

	ExternalMarkerHost(CodeTarget target, CodeEditor editor) {
		super(target);
		this.editor = editor;
	}

	static int severity(String s) {
		if(s == null)
			return CodeMarker.ERROR;
		switch(s.toLowerCase()) {
			case "warning":
				return CodeMarker.WARNING;
			case "info":
				return CodeMarker.INFO;
			default:
				return CodeMarker.ERROR;
		}
	}

	@Override
	Object key() {
		return editor;
	}

	@Override
	Object add(int start, int end, String severity, String message, String fixDescription, String replacement) {
		CodeMarker m = new CodeMarker(start, end, severity(severity), message, fixDescription, replacement);
		if(!editor.addMarker(m))
			throw new IllegalStateException("the editor did not accept the marker");
		return m;
	}

	@Override
	void clear() {
		editor.clearMarkers();
	}

	@Override
	boolean apply(int start, int end, Object handle) {
		return handle instanceof CodeMarker && editor.applyQuickFix((CodeMarker)handle);
	}

	@Override
	boolean pending(int start, int end, Object handle) {
		return handle instanceof CodeMarker && ((CodeMarker)handle).hasQuickFix() && editor.hasMarker((CodeMarker)handle);
	}
}
