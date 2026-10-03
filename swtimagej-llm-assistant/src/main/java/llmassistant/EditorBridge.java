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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import ij.IJ;
import ij.WindowManager;
import ij.plugin.frame.Editor;
import ij.plugin.frame.swt.WindowSwt;

/**
 * Access to the SWTImageJ editors: the tabs of the Script Explorer
 * (ij.plugin.frame.ScriptExplorer, preferred by default) and standalone
 * ij.plugin.frame.Editor windows.
 * <p>
 * The Script Explorer is accessed without a compile-time dependency (it only
 * exists in recent SWTImageJ versions): it is found by class name in the
 * WindowManager, its CTabFolder by walking its public widget tree, and each tab
 * carries its embedded Editor as CTabItem data - the same contract the Script
 * Explorer itself uses. Older SWTImageJ versions fall back to standalone
 * editors. All widget access is marshalled onto the SWT display thread, so the
 * methods can be called from any thread.
 */
public class EditorBridge {

	public static final String EXPLORER_CLASS = "ij.plugin.frame.ScriptExplorer";
	private static final String HOOK_KEY = "llmassistant.hooked";

	private final LLMSettings settings;
	/** Explicitly selected target editor (null = automatic). */
	private volatile Editor selected;
	/** Last standalone editor that was ImageJ's active window. */
	private volatile Editor lastStandalone;
	/** Called (on the UI thread) when the Script Explorer's selected tab changes. */
	private volatile Runnable tabListener;

	public EditorBridge(LLMSettings settings) {
		this.settings = settings;
	}

	private static <T> T sync(java.util.function.Supplier<T> s) {
		AtomicReference<T> ref = new AtomicReference<>();
		Display.getDefault().syncExec(() -> ref.set(s.get()));
		return ref.get();
	}

	/* ================================================================ Script Explorer */

	/** True if this SWTImageJ version contains the Script Explorer. */
	public static boolean explorerAvailable() {
		try {
			Class.forName(EXPLORER_CLASS);
			return true;
		} catch(Throwable t) {
			return false;
		}
	}

	public boolean useExplorer() {
		return settings.useScriptExplorer && explorerAvailable();
	}

	/** The open Script Explorer window (a WindowSwt), or null. */
	public static WindowSwt findExplorer() {
		Object[] wins = WindowManager.getNonImageWindows();
		if(wins == null)
			return null;
		for(Object w : wins) {
			if(w instanceof WindowSwt && w.getClass().getName().equals(EXPLORER_CLASS)) {
				WindowSwt ws = (WindowSwt)w;
				Boolean alive = sync(() -> ws.getShell() != null && !ws.getShell().isDisposed());
				if(alive)
					return ws;
			}
		}
		return null;
	}

	/** Returns the open Script Explorer or opens one (plugins + macros folders). */
	public WindowSwt openExplorer() {
		WindowSwt ex = findExplorer();
		if(ex != null || !explorerAvailable())
			return ex;
		try {
			Object o = Class.forName(EXPLORER_CLASS).getConstructor().newInstance();
			return o instanceof WindowSwt ? (WindowSwt)o : null;
		} catch(Throwable t) {
			IJ.log("LLM Assistant: could not open the Script Explorer: " + t);
			return null;
		}
	}

	/** Brings the Script Explorer to the front (opening it if necessary). */
	public void showExplorer() {
		WindowSwt ex = openExplorer();
		if(ex != null)
			Display.getDefault().asyncExec(() -> {
				Shell sh = ex.getShell();
				if(sh != null && !sh.isDisposed()) {
					sh.setMinimized(false);
					sh.setVisible(true);
					sh.forceActive();
				}
			});
	}

	/** Must be called on the UI thread. */
	private static CTabFolder findTabFolder(Control c) {
		if(c instanceof CTabFolder)
			return (CTabFolder)c;
		if(c instanceof Composite) {
			for(Control child : ((Composite)c).getChildren()) {
				CTabFolder f = findTabFolder(child);
				if(f != null)
					return f;
			}
		}
		return null;
	}

	/** Must be called on the UI thread. */
	private CTabFolder tabFolder(WindowSwt ex) {
		if(ex == null || ex.getShell() == null || ex.getShell().isDisposed())
			return null;
		CTabFolder f = findTabFolder(ex.getShell());
		if(f != null && f.getData(HOOK_KEY) == null) {
			f.setData(HOOK_KEY, Boolean.TRUE);
			f.addListener(SWT.Selection, _ -> fireTabChange());
			f.addListener(SWT.Dispose, _ -> fireTabChange());
		}
		return f;
	}

	private void fireTabChange() {
		Runnable r = tabListener;
		if(r != null)
			Display.getDefault().asyncExec(r);
	}

	public void setTabListener(Runnable r) {
		tabListener = r;
	}

	/** Editors in the Script Explorer's tabs, in tab order. */
	public List<Editor> explorerEditors() {
		WindowSwt ex = findExplorer();
		if(ex == null)
			return new ArrayList<>();
		return sync(() -> {
			List<Editor> l = new ArrayList<>();
			CTabFolder f = tabFolder(ex);
			if(f != null && !f.isDisposed())
				for(CTabItem it : f.getItems())
					if(it.getData() instanceof Editor)
						l.add((Editor)it.getData());
			return l;
		});
	}

	/** Editor of the Script Explorer's selected tab, or null. */
	public Editor explorerActiveEditor() {
		WindowSwt ex = findExplorer();
		if(ex == null)
			return null;
		return sync(() -> {
			CTabFolder f = tabFolder(ex);
			CTabItem it = f == null || f.isDisposed() ? null : f.getSelection();
			return it != null && it.getData() instanceof Editor ? (Editor)it.getData() : null;
		});
	}

	/** The Script Explorer tab holding the editor, or null (UI thread). */
	private CTabItem tabOf(Editor ed) {
		WindowSwt ex = findExplorer();
		CTabFolder f = tabFolder(ex);
		if(f == null || f.isDisposed())
			return null;
		for(CTabItem it : f.getItems())
			if(it.getData() == ed)
				return it;
		return null;
	}

	public boolean isInExplorer(Editor ed) {
		return ed != null && sync(() -> tabOf(ed) != null);
	}

	/**
	 * Wraps an embedded Editor in a new, selected Script Explorer tab - mirrors
	 * ScriptExplorer.createTab(), including closing the editor (WindowManager
	 * cleanup, save prompt) and disposing its hidden shell when the tab closes.
	 */
	private CTabItem addTab(WindowSwt ex, Editor editor, String label) {
		return sync(() -> {
			CTabFolder f = tabFolder(ex);
			if(f == null || f.isDisposed())
				return null;
			Composite c = editor.getComposite();
			c.setParent(f);
			CTabItem item = new CTabItem(f, SWT.CLOSE);
			item.setText(label);
			item.setControl(c);
			item.setData(editor);
			f.setSelection(item);
			item.addDisposeListener(_ -> {
				editor.close();
				if(!editor.getShell().isDisposed())
					editor.getShell().dispose();
			});
			Shell sh = ex.getShell();
			sh.setMinimized(false);
			sh.setVisible(true);
			return item;
		});
	}

	/** Opens new content in a Script Explorer tab; returns null if that is not possible. */
	public Editor openInExplorer(String title, String text) {
		WindowSwt ex = openExplorer();
		if(ex == null)
			return null;
		Editor editor = new Editor(24, 80, 0, Editor.MENU_BAR, true, true);
		editor.create(title, text);
		if(addTab(ex, editor, title) == null)
			return null;
		fireTabChange();
		return editor;
	}

	/** Opens a file in a Script Explorer tab (or selects the tab if already open). */
	public Editor openFileInExplorer(File file) {
		WindowSwt ex = openExplorer();
		if(ex == null)
			return null;
		Editor existing = sync(() -> {
			CTabFolder f = tabFolder(ex);
			if(f == null)
				return null;
			for(CTabItem it : f.getItems())
				if(file.getName().equals(it.getText()) && it.getData() instanceof Editor) {
					f.setSelection(it);
					return (Editor)it.getData();
				}
			return null;
		});
		if(existing != null)
			return existing;
		Editor editor = new Editor(24, 80, 0, Editor.MENU_BAR, true, true);
		editor.open(file.getParent() + File.separator, file.getName());
		if(addTab(ex, editor, file.getName()) == null)
			return null;
		fireTabChange();
		return editor;
	}

	/* ================================================================ all editors */

	/** Standalone editor windows (not embedded in the Script Explorer). */
	public List<Editor> standaloneEditors() {
		List<Editor> explorer = explorerEditors();
		List<Editor> list = new ArrayList<>();
		Object[] wins = WindowManager.getNonImageWindows();
		if(wins != null)
			for(Object w : wins)
				if(w instanceof Editor && !explorer.contains(w) && isAlive((Editor)w) && isShown((Editor)w))
					list.add((Editor)w);
		return list;
	}

	/** Script Explorer tabs first, then standalone windows. */
	public List<Editor> listEditors() {
		List<Editor> l = explorerEditors();
		l.addAll(standaloneEditors());
		return l;
	}

	static boolean isAlive(Editor ed) {
		if(ed == null)
			return false;
		Boolean ok = sync(() -> {
			Shell sh = ed.getShell();
			StyledText ta = ed.getTextArea();
			return sh != null && !sh.isDisposed() && ta != null && !ta.isDisposed();
		});
		return ok;
	}

	/** Excludes embedded editors whose composite lives in some other container. */
	private static boolean isShown(Editor ed) {
		return sync(() -> {
			Composite c = ed.getComposite();
			return c == null || c.isDisposed() || c.getShell() == ed.getShell();
		});
	}

	public static String titleOf(Editor ed) {
		try {
			return ed.getTitle();
		} catch(Exception e) {
			return "Editor";
		}
	}

	/** Title with location prefix for lists. */
	public String labelOf(Editor ed) {
		return (isInExplorer(ed) ? "Explorer: " : "Window: ") + titleOf(ed);
	}

	public void setSelected(Editor ed) {
		selected = ed;
	}

	/** Called whenever a shell is activated, to remember the last standalone editor. */
	public void noteActiveWindow() {
		Object w = WindowManager.getActiveWindow();
		if(w instanceof Editor)
			lastStandalone = (Editor)w;
		else if(w != null && w.getClass().getName().equals(EXPLORER_CLASS))
			lastStandalone = null; // the Script Explorer was used last
	}

	/**
	 * Resolves the editor to work with: explicit selection, then (by default) the
	 * Script Explorer's selected tab, then the last active standalone editor or
	 * other open editors.
	 */
	public Editor target() {
		List<Editor> open = listEditors();
		if(selected != null && open.contains(selected))
			return selected;
		Editor exActive = useExplorer() ? explorerActiveEditor() : null;
		if(exActive != null)
			return exActive;
		if(lastStandalone != null && open.contains(lastStandalone))
			return lastStandalone;
		Object w = WindowManager.getActiveWindow();
		if(w instanceof Editor && open.contains(w))
			return (Editor)w;
		Editor inst = Editor.getInstance();
		if(inst != null && open.contains(inst))
			return inst;
		if(Editor.currentMacroEditor != null && open.contains(Editor.currentMacroEditor))
			return Editor.currentMacroEditor;
		if(!useExplorer()) {
			Editor ea = explorerActiveEditor();
			if(ea != null)
				return ea;
		}
		return open.isEmpty() ? null : open.get(open.size() - 1);
	}

	/* ================================================================ text access */

	public String getText(Editor ed) {
		return ed == null ? null : ed.getText();
	}

	public String getSelection(Editor ed) {
		if(ed == null)
			return "";
		return sync(() -> {
			StyledText ta = ed.getTextArea();
			return ta != null && !ta.isDisposed() ? ta.getSelectionText() : "";
		});
	}

	/** Makes the editor visible: selects its Script Explorer tab or shows its window. UI thread. */
	private void reveal(Editor ed) {
		CTabItem it = tabOf(ed);
		if(it != null) {
			it.getParent().setSelection(it);
			WindowSwt ex = findExplorer();
			if(ex != null)
				ex.getShell().setMinimized(false);
		} else if(ed.getShell() != null && !ed.getShell().isDisposed()) {
			ed.getShell().setMinimized(false);
			ed.getShell().setVisible(true);
		}
	}

	public void replaceAll(Editor ed, String text) {
		Display.getDefault().syncExec(() -> {
			StyledText ta = ed.getTextArea();
			if(ta == null || ta.isDisposed())
				return;
			// replaceTextRange keeps the editor's modify listeners (unsaved flag, styling) working
			ta.replaceTextRange(0, ta.getCharCount(), text);
			ta.setCaretOffset(0);
			reveal(ed);
		});
	}

	/** Inserts at the caret, replacing the current selection if there is one. */
	public void insertAtCaret(Editor ed, String text) {
		Display.getDefault().syncExec(() -> {
			StyledText ta = ed.getTextArea();
			if(ta == null || ta.isDisposed())
				return;
			org.eclipse.swt.graphics.Point sel = ta.getSelectionRange();
			String t = text.endsWith("\n") ? text : text + "\n";
			ta.replaceTextRange(sel.x, sel.y, t);
			ta.setCaretOffset(sel.x + t.length());
			reveal(ed);
		});
	}

	public void append(Editor ed, String text) {
		Display.getDefault().syncExec(() -> {
			StyledText ta = ed.getTextArea();
			if(ta == null || ta.isDisposed())
				return;
			String cur = ta.getText();
			String prefix = cur.isEmpty() || cur.endsWith("\n") ? "" : "\n";
			ta.append(prefix + text);
			ta.setCaretOffset(ta.getCharCount());
			ta.showSelection();
			reveal(ed);
		});
	}

	/**
	 * Opens new content: in a Script Explorer tab by default, otherwise (or if the
	 * explorer is unavailable) in a standalone editor window. The extension of the
	 * title (.ijm, .js, .bsh, .py, .java) selects the language support.
	 */
	public Editor openNew(String title, String text) {
		Editor ed = useExplorer() ? openInExplorer(title, text) : null;
		if(ed == null) {
			ed = new Editor(title);
			ed.create(title, text);
		}
		selected = null; // follow the new tab / window automatically
		if(!isInExplorer(ed))
			lastStandalone = ed;
		return ed;
	}

	public static String extensionFor(String language) {
		if(language == null)
			return ".ijm";
		switch(language.toLowerCase()) {
			case "js":
			case "javascript":
				return ".js";
			case "bsh":
			case "beanshell":
				return ".bsh";
			case "py":
			case "python":
			case "jython":
				return ".py";
			case "java":
				return ".java";
			default:
				return ".ijm";
		}
	}

	/** Guesses the script language from an editor title. */
	public static String languageOf(Editor ed) {
		String t = ed == null ? "" : titleOf(ed).toLowerCase();
		if(t.endsWith(".js"))
			return "js";
		if(t.endsWith(".bsh"))
			return "bsh";
		if(t.endsWith(".py"))
			return "py";
		if(t.endsWith(".java"))
			return "java";
		return "ijm";
	}
}
