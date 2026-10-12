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

/** An SWTImageJ editor: a Script Explorer tab or a standalone editor window. */
final class ImageJTarget extends CodeTarget {

	private final EditorBridge bridge;
	final Editor editor;

	ImageJTarget(EditorBridge bridge, Editor editor) {
		this.bridge = bridge;
		this.editor = editor;
	}

	@Override
	String title() {
		return EditorBridge.titleOf(editor);
	}

	@Override
	String language() {
		return EditorBridge.languageOf(editor);
	}

	@Override
	String location() {
		return bridge.isInExplorer(editor) ? "Explorer" : "Window";
	}

	@Override
	String text() {
		return bridge.getText(editor);
	}

	@Override
	String selection() {
		return bridge.getSelection(editor);
	}

	@Override
	void replaceAll(String text) {
		bridge.replaceAll(editor, text);
	}

	@Override
	void insertAtCaret(String text) {
		bridge.insertAtCaret(editor, text);
	}

	@Override
	void append(String text) {
		bridge.append(editor, text);
	}

	@Override
	boolean isOpen() {
		return EditorBridge.isAlive(editor);
	}

	@Override
	Editor imagejEditor() {
		return editor;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ImageJTarget && ((ImageJTarget)o).editor == editor;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(editor);
	}
}
