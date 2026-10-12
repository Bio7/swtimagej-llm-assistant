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
import java.util.List;

import ij.IJ;
import ij.swt.CodeEditor;
import ij.swt.CodeEditorProvider;
import ij.swt.CodeEditors;

/**
 * An editor of the application that embeds SWTImageJ, supplied by a registered
 * {@link CodeEditorProvider}. Only used if this SWTImageJ version contains
 * {@link CodeEditors} (see {@link EditorBridge#externalEditorsAvailable()}):
 * this class is the only one which refers to these types, so older SWTImageJ
 * versions still work.
 */
final class ExternalTarget extends CodeTarget {

	final CodeEditorProvider provider;
	final CodeEditor editor;

	private ExternalTarget(CodeEditorProvider provider, CodeEditor editor) {
		this.provider = provider;
		this.editor = editor;
	}

	static boolean hasProviders() {
		return !CodeEditors.getProviders().isEmpty();
	}

	/** The open editors of all registered providers. */
	static List<CodeTarget> list() {
		List<CodeTarget> list = new ArrayList<>();
		for(CodeEditorProvider p : CodeEditors.getProviders()) {
			try {
				List<CodeEditor> eds = p.getEditors();
				if(eds != null)
					for(CodeEditor e : eds)
						if(e != null && e.isOpen())
							list.add(new ExternalTarget(p, e));
			} catch(Throwable t) {
				IJ.log("LLM Assistant: editor provider '" + name(p) + "' failed: " + t);
			}
		}
		return list;
	}

	/** The active editor of the first provider which has one, or null. */
	static CodeTarget active() {
		for(CodeEditorProvider p : CodeEditors.getProviders()) {
			try {
				CodeEditor e = p.getActiveEditor();
				if(e != null && e.isOpen())
					return new ExternalTarget(p, e);
			} catch(Throwable t) {
				IJ.log("LLM Assistant: editor provider '" + name(p) + "' failed: " + t);
			}
		}
		return null;
	}

	/** Opens new code with the provider of the given target; null if not possible. */
	static CodeTarget openNew(CodeTarget near, String title, String text) {
		if(!(near instanceof ExternalTarget))
			return null;
		CodeEditorProvider p = ((ExternalTarget)near).provider;
		try {
			CodeEditor e = p.openEditor(title, text);
			return e == null ? null : new ExternalTarget(p, e);
		} catch(Throwable t) {
			IJ.log("LLM Assistant: editor provider '" + name(p) + "' could not open '" + title + "': " + t);
			return null;
		}
	}

	private static String name(CodeEditorProvider p) {
		try {
			String n = p.getName();
			return n == null || n.isBlank() ? "Application" : n;
		} catch(Throwable t) {
			return "Application";
		}
	}

	@Override
	String title() {
		try {
			String t = editor.getTitle();
			return t == null ? "Editor" : t;
		} catch(Throwable t) {
			return "Editor";
		}
	}

	@Override
	String language() {
		return normalizeLanguage(editor.getLanguage());
	}

	@Override
	String location() {
		return name(provider);
	}

	@Override
	String text() {
		return editor.getText();
	}

	@Override
	String selection() {
		String s = editor.getSelectedText();
		return s == null ? "" : s;
	}

	@Override
	void replaceAll(String text) {
		editor.setText(text);
		editor.show();
	}

	@Override
	void insertAtCaret(String text) {
		editor.insertText(text.endsWith("\n") ? text : text + "\n");
		editor.show();
	}

	@Override
	void append(String text) {
		String cur = editor.getText();
		String prefix = cur == null || cur.isEmpty() || cur.endsWith("\n") ? "" : "\n";
		editor.appendText(prefix + text);
		editor.show();
	}

	@Override
	boolean isOpen() {
		try {
			return editor.isOpen();
		} catch(Throwable t) {
			return false;
		}
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ExternalTarget && ((ExternalTarget)o).editor.equals(editor);
	}

	@Override
	public int hashCode() {
		return editor.hashCode();
	}
}
