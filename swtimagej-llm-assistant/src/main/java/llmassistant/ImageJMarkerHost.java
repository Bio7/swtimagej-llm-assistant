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

/** Markers in an SWTImageJ editor (only created if {@link EditorMarkers#available()}). */
final class ImageJMarkerHost extends MarkerHost {

	private final Editor editor;

	ImageJMarkerHost(CodeTarget target, Editor editor) {
		super(target);
		this.editor = editor;
	}

	@Override
	Object key() {
		return editor;
	}

	@Override
	Object add(int start, int end, String severity, String message, String fixDescription, String replacement) {
		return MarkerSupport.add(editor, start, end, severity, message, fixDescription, replacement);
	}

	@Override
	void clear() {
		MarkerSupport.clear(editor);
	}

	@Override
	boolean apply(int start, int end, Object handle) {
		return MarkerSupport.apply(editor, start, end, handle);
	}

	@Override
	boolean pending(int start, int end, Object handle) {
		return MarkerSupport.pending(editor, start, end, handle);
	}
}
