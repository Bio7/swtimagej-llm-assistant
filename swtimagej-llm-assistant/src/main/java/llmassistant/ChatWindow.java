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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import ij.IJ;
import ij.macro.Interpreter;
import ij.plugin.frame.Editor;

/**
 * Chat window of the LLM Assistant. The conversation runs on a background
 * thread; the model may call SWTImageJ tools ({@link ImageJTools}) in several
 * rounds before it answers.
 */
public class ChatWindow implements ImageJTools.Host {

	private static ChatWindow instance;

	private final Display display;
	private final LLMSettings settings = LLMSettings.get();
	private final EditorBridge editors = new EditorBridge(settings);
	private final ImageJTools tools;
	private OpenAIClient client;

	private Shell shell;
	private StyledText transcript;
	private Text input;
	private Combo modelCombo, editorCombo, langCombo, codeCombo;
	private Button sendButton, stopButton, newButton, attachButton;
	/** Check items of the "Attach" drop-down menu. */
	private MenuItem attachImage, attachEditor, attachSnapshot, useDocs;
	private Label status;
	private Font monoFont, boldFont;
	private Color codeBg, dimFg, userFg, errorFg;
	private Listener activateFilter;
	private org.eclipse.swt.graphics.Image gearIcon;
	private BusyIndicator busyIndicator;

	private final List<Editor> editorItems = new ArrayList<>();
	private final List<String[]> codeBlocks = new ArrayList<>(); // {language, code}
	private final List<Map<String, Object>> history = Collections.synchronizedList(new ArrayList<>());
	private final AtomicBoolean busy = new AtomicBoolean(false);
	private volatile boolean cancelled;
	private volatile boolean runningCode;
	/** Set by quick actions that must include a snapshot regardless of the checkbox. */
	private boolean forceSnapshot;
	/** Images kept in the history that is re-sent to the model (older ones become text). */
	private static final int KEEP_IMAGES = 2;

	private static final String[] LANG_IDS = {"ijm", "java", "js", "bsh", "py"};
	private static final String[] LANG_NAMES = {"ImageJ macro", "Java", "JavaScript", "BeanShell", "Python (Jython)"};
	private static final Pattern FENCE = Pattern.compile("```([A-Za-z0-9_+#.-]*)[^\\n]*\\n(.*?)```", Pattern.DOTALL);

	/** Opens the chat window or brings the existing one to front (thread safe). */
	public static void showWindow() {
		Display d = Display.getDefault();
		d.asyncExec(() -> {
			if(instance != null && instance.shell != null && !instance.shell.isDisposed()) {
				instance.shell.setMinimized(false);
				instance.shell.forceActive();
				instance.refreshEditors();
				return;
			}
			instance = new ChatWindow(d);
			instance.create();
		});
	}

	private ChatWindow(Display display) {
		this.display = display;
		this.tools = new ImageJTools(editors, settings, this);
		this.client = new OpenAIClient(settings);
	}

	/* ================================================================== UI */

	private void create() {
		shell = new Shell(display, SWT.SHELL_TRIM);
		shell.setText("LLM Assistant " + LLM_Assistant.VERSION + " - SWTImageJ");
		shell.setLayout(new GridLayout(1, false));
		createResources();

		/* ---- top bar: model / editor / language ---- */
		Composite top = new Composite(shell, SWT.NONE);
		top.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		top.setLayout(new GridLayout(10, false));
		new Label(top, SWT.NONE).setText("Model:");
		modelCombo = new Combo(top, SWT.DROP_DOWN);
		GridData mg = new GridData(SWT.FILL, SWT.CENTER, true, false);
		mg.widthHint = 170;
		modelCombo.setLayoutData(mg);
		fillModels();
		modelCombo.setToolTipText("Select or type a model id");
		modelCombo.addListener(SWT.Selection, _ -> modelChanged());
		modelCombo.addListener(SWT.DefaultSelection, _ -> modelChanged());
		button(top, "\u21bb", "Fetch model list from the server", _ -> fetchModels());

		new Label(top, SWT.NONE).setText("Editor:");
		editorCombo = new Combo(top, SWT.READ_ONLY);
		GridData eg = new GridData(SWT.FILL, SWT.CENTER, true, false);
		eg.widthHint = 170;
		editorCombo.setLayoutData(eg);
		editorCombo.setToolTipText("Editor the assistant reads and writes. Auto = selected Script Explorer tab (default) or the last used editor window.");
		editorCombo.addListener(SWT.Selection, _ -> editorChanged());
		button(top, "\u21bb", "Refresh the list of open editors", _ -> refreshEditors());

		new Label(top, SWT.NONE).setText("Write:");
		langCombo = new Combo(top, SWT.READ_ONLY);
		langCombo.setItems(LANG_NAMES);
		int li = java.util.Arrays.asList(LANG_IDS).indexOf(settings.defaultLanguage);
		langCombo.select(li < 0 ? 0 : li);
		langCombo.setToolTipText("Preferred language for generated code");
		Button ex = button(top, "Script Explorer", "Open / show the Script Explorer", _ -> {
			editors.showExplorer();
			refreshEditors();
		});
		ex.setEnabled(EditorBridge.explorerAvailable());
		/* settings: one gear button with a drop-down menu */
		Button settingsButton = new Button(top, SWT.PUSH);
		gearIcon = Icons.gear(display, display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND).getRGB());
		settingsButton.setImage(gearIcon);
		settingsButton.setText("\u25be");
		settingsButton.setToolTipText("Settings: General Preferences, Documents");
		Menu settingsMenu = new Menu(shell, SWT.POP_UP);
		MenuItem prefsItem = new MenuItem(settingsMenu, SWT.PUSH);
		prefsItem.setText("General Preferences...");
		prefsItem.addListener(SWT.Selection, _ -> PreferencesDialog.open(shell, this::settingsChanged));
		MenuItem docsItem = new MenuItem(settingsMenu, SWT.PUSH);
		docsItem.setText("Documents...");
		docsItem.addListener(SWT.Selection, _ -> DocumentsDialog.show(shell, this::documentsChanged));
		settingsButton.addListener(SWT.Selection, _ -> {
			org.eclipse.swt.graphics.Rectangle b = settingsButton.getBounds();
			settingsMenu.setLocation(settingsButton.getParent().toDisplay(b.x, b.y + b.height));
			settingsMenu.setVisible(true);
		});

		/* ---- transcript + input ---- */
		SashForm sash = new SashForm(shell, SWT.VERTICAL);
		sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		transcript = new StyledText(sash, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
		transcript.setMargins(8, 6, 8, 6);
		transcript.setWordWrap(true);

		input = new Text(sash, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		input.setMessage("Ask about the image or editor, or describe the macro you need. Enter sends, Shift+Enter adds a new line.");
		input.addListener(SWT.KeyDown, e -> {
			if(e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
				if((e.stateMask & SWT.SHIFT) != 0)
					return; // Shift+Enter: new line
				e.doit = false;
				send();
			}
		});
		/* the message history and the input have the same size (sash can still be dragged) */
		sash.setWeights(new int[]{50, 50});
		/* same font size for both areas */
		input.setFont(transcript.getFont());

		/* rows below the input: code actions, then quick actions menu, attachments + send */
		Composite bottom = shell;

		/* code block actions */
		Composite code = new Composite(bottom, SWT.NONE);
		code.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		code.setLayout(new GridLayout(7, false));
		new Label(code, SWT.NONE).setText("Code:");
		codeCombo = new Combo(code, SWT.READ_ONLY);
		codeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		codeCombo.setToolTipText("Code blocks from the conversation");
		button(code, "Insert", "Insert at the caret of the target editor", _ -> codeAction("insert"));
		button(code, "Replace", "Replace the target editor content", _ -> codeAction("replace"));
		newButton = button(code, "New tab", "Open the code in a new Script Explorer tab", _ -> codeAction("new"));
		button(code, "Run", "Run the code now", _ -> codeAction("run"));
		button(code, "Copy", "Copy to clipboard", _ -> codeAction("copy"));

		Composite actions = new Composite(bottom, SWT.NONE);
		actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		actions.setLayout(new GridLayout(7, false));
		Button quickButton = new Button(actions, SWT.PUSH);
		quickButton.setText("Quick actions \u25be");
		quickButton.setToolTipText("Predefined prompts for the editor code and the active image");
		quickMenu = createQuickMenu();
		quickButton.addListener(SWT.Selection, _ -> {
			org.eclipse.swt.graphics.Rectangle b = quickButton.getBounds();
			org.eclipse.swt.graphics.Point p = quickButton.getParent().toDisplay(b.x, b.y + b.height);
			quickMenu.setLocation(p);
			quickMenu.setVisible(true);
		});
		/* what is sent along with each message: one drop-down menu with check items */
		attachButton = new Button(actions, SWT.PUSH);
		attachButton.setToolTipText("Choose what is attached to your messages");
		Menu attachMenu = new Menu(shell, SWT.POP_UP);
		attachImage = checkItem(attachMenu, "Attach image info", "Dimensions, calibration, selection, open images and editors", settings.attachImageInfo);
		attachSnapshot = checkItem(attachMenu, "Attach snapshot of the active image", "Send the active image as a picture (needs a vision model)", settings.attachSnapshot && settings.enableVision);
		attachSnapshot.setEnabled(settings.enableVision);
		attachEditor = checkItem(attachMenu, "Attach editor code", "The code of the target editor and its selection", settings.attachEditorText);
		useDocs = checkItem(attachMenu, "Use documents", "Attach matching excerpts from the reference documents (\u2699 > Documents...)", settings.attachDocuments);
		attachButton.addListener(SWT.Selection, _ -> {
			org.eclipse.swt.graphics.Rectangle b = attachButton.getBounds();
			attachMenu.setLocation(attachButton.getParent().toDisplay(b.x, b.y + b.height));
			attachMenu.setVisible(true);
		});
		updateAttachButton();
		status = new Label(actions, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		busyIndicator = new BusyIndicator(actions, status);
		busyIndicator.placeBefore(status);
		button(actions, "Clear", "Start a new conversation: removes all messages and code blocks", _ -> clear());
		stopButton = button(actions, "Stop", "Cancel the current request", _ -> stop());
		stopButton.setEnabled(false);
		sendButton = button(actions, "Send", "Send (Enter)", _ -> send());

		/* remember the last active editor while the user switches windows */
		activateFilter = _ -> editors.noteActiveWindow();
		editors.setTabListener(this::refreshEditors);
		display.addFilter(SWT.Activate, activateFilter);
		shell.addListener(SWT.Activate, _ -> refreshEditors());
		shell.addListener(SWT.Dispose, _ -> dispose());

		updateNewButton();
		refreshEditors();
		welcome();
		startIndexing();
		shell.setSize(860, 760);
		PreferencesDialog.center(shell, null);
		shell.open();
		input.setFocus();
	}

	private void createResources() {
		FontData fd = display.getSystemFont().getFontData()[0];
		String mono = IJ.isWindows() ? "Consolas" : IJ.isMacOSX() ? "Menlo" : "Monospace";
		monoFont = new Font(display, mono, fd.getHeight(), SWT.NORMAL);
		boldFont = new Font(display, fd.getName(), fd.getHeight(), SWT.BOLD);
		RGB bg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
		RGB fg = display.getSystemColor(SWT.COLOR_LIST_FOREGROUND).getRGB();
		codeBg = new Color(display, blend(bg, fg, 0.08));
		dimFg = new Color(display, blend(fg, bg, 0.45));
		userFg = new Color(display, blend(new RGB(30, 90, 200), fg, 0.15));
		errorFg = new Color(display, new RGB(200, 40, 40));
	}

	private static RGB blend(RGB a, RGB b, double t) {
		return new RGB((int)(a.red + (b.red - a.red) * t), (int)(a.green + (b.green - a.green) * t), (int)(a.blue + (b.blue - a.blue) * t));
	}

	private Button button(Composite parent, String text, String tip, Listener l) {
		Button b = new Button(parent, SWT.PUSH);
		b.setText(text);
		b.setToolTipText(tip);
		b.addListener(SWT.Selection, l);
		return b;
	}

	/* ================================================================== quick actions */

	private Menu quickMenu;
	private MenuItem analyzeItem;

	private static final String P_EXPLAIN = "Explain what the code in the editor does, step by step.";
	private static final String P_EXPLAIN_SEL = "Explain the code selected in the editor, step by step.";
	private static final String P_FIX = "Review the code in the editor, find bugs or problems and write an improved version into the editor.";
	private static final String P_COMMENTS = "Add clear comments to the code in the editor and write the result back into the editor.";
	private static final String P_JAVA = "Convert the code in the editor into an equivalent SWTImageJ Java plugin (a class with an underscore in its name implementing ij.plugin.PlugIn, or PlugInFilter if it processes the active image), open it in a new tab, compile and test it with run_code (language java), and fix any compiler errors.";
	private static final String P_FIND = "Find errors and problems in the code in the editor and mark them with quick fixes: for Java call check_code first; in any case read the code with get_editor_text (line_numbers=true) and review it (wrong ImageJ command names or option strings - verify with search_commands -, typos, syntax errors, logic errors, missing checks). Then call mark_issues once with all problems, each with a precise 'match' where possible and a 'replacement' containing the corrected text. Do not change the editor text yourself. Finish with a short summary of the problems.";
	private static final String P_DESCRIBE = "Describe the active image (type, dimensions, calibration, intensity statistics) and suggest suitable processing steps.";
	private static final String P_ANALYZE = "Look at the attached image: describe what it shows (structures, staining, quality, artifacts, noise, uneven background) and suggest a suitable analysis workflow with ImageJ commands.";
	private static final String P_MACRO = "Inspect the active image and write an ImageJ macro into a new editor tab that performs a sensible standard analysis (e.g. background subtraction, thresholding, particle/object measurement). Explain the steps briefly.";

	/** Drop-down menu of predefined prompts, grouped by what they work on. */
	private Menu createQuickMenu() {
		Menu m = new Menu(shell, SWT.POP_UP);
		header(m, "Editor code");
		quickItem(m, "Explain code", P_EXPLAIN, false);
		quickItem(m, "Explain selection", P_EXPLAIN_SEL, false);
		quickItem(m, "Fix / improve", P_FIX, false);
		quickItem(m, "Add comments", P_COMMENTS, false);
		quickItem(m, "Convert to Java plugin", P_JAVA, false);
		new MenuItem(m, SWT.SEPARATOR);
		header(m, "Markers & quick fixes");
		MenuItem find = quickItem(m, "Find errors && add quick fixes", P_FIND, false);
		actionItem(m, "Check Java code now (compile && mark errors)", this::checkNow);
		actionItem(m, "Apply all quick fixes", this::applyAllFixes);
		actionItem(m, "Clear markers", this::clearMarkers);
		actionItem(m, "Test markers (places an info marker)", this::testMarkers);
		new MenuItem(m, SWT.SEPARATOR);
		header(m, "Active image");
		quickItem(m, "Describe image", P_DESCRIBE, false);
		analyzeItem = quickItem(m, "Analyze image (sends snapshot) \u25c9", P_ANALYZE, true);
		quickItem(m, "Write analysis macro", P_MACRO, false);
		/* state is evaluated when the menu opens */
		m.addListener(SWT.Show, _ -> {
			for(MenuItem it : m.getItems())
				if(it.getData() instanceof String)
					it.setEnabled(!busy.get());
			analyzeItem.setEnabled(!busy.get() && settings.enableVision);
			find.setEnabled(!busy.get()); // explains the missing API when clicked
		});
		return m;
	}

	private static void header(Menu m, String text) {
		MenuItem h = new MenuItem(m, SWT.PUSH);
		h.setText(text);
		h.setEnabled(false); // section label
	}

	private MenuItem quickItem(Menu m, String label, String prompt, boolean snapshot) {
		MenuItem it = new MenuItem(m, SWT.PUSH);
		it.setText("    " + label);
		it.setData(prompt);
		it.addListener(SWT.Selection, _ -> runQuick(prompt, snapshot));
		return it;
	}

	/** A check item of the Attach menu; changing it updates the button label. */
	private MenuItem checkItem(Menu m, String label, String tip, boolean selected) {
		MenuItem it = new MenuItem(m, SWT.CHECK);
		it.setText(label);
		it.setToolTipText(tip);
		it.setSelection(selected);
		it.addListener(SWT.Selection, _ -> updateAttachButton());
		return it;
	}

	/** Shows how many attachments are switched on, e.g. "Attach (2)", and lists them in the tooltip. */
	private void updateAttachButton() {
		if(attachButton == null || attachButton.isDisposed())
			return;
		List<String> on = new ArrayList<>();
		if(attachImage.getSelection())
			on.add("image info");
		if(attachSnapshot.getSelection() && attachSnapshot.getEnabled())
			on.add("snapshot");
		if(attachEditor.getSelection())
			on.add("editor code");
		if(useDocs.getSelection())
			on.add("documents");
		attachButton.setText("Attach" + (on.isEmpty() ? "" : " (" + on.size() + ")") + " \u25be");
		attachButton.setToolTipText(on.isEmpty() ? "Nothing is attached to your messages - click to choose" : "Attached to your messages: " + String.join(", ", on) + " - click to change");
		attachButton.getParent().layout();
	}

	private MenuItem actionItem(Menu m, String label, Runnable action) {
		MenuItem it = new MenuItem(m, SWT.PUSH);
		it.setText("    " + label);
		it.addListener(SWT.Selection, _ -> action.run());
		return it;
	}

	/** Runs a marker action on a background thread for the target editor, reporting problems in the transcript. */
	private void markerAction(java.util.function.Function<Editor, String> action) {
		new Thread(() -> {
			if(!EditorMarkers.available()) {
				error(EditorMarkers.UNAVAILABLE + "\nReason: " + EditorMarkers.unavailableReason());
				return;
			}
			Editor ed = editors.target();
			if(ed == null) {
				info("No editor is open (open a macro or Java file in the Script Explorer or an editor window).");
				return;
			}
			try {
				info(action.apply(ed).replace("\n", "\n    "));
			} catch(Throwable t) {
				error("Marker action failed: " + t);
			}
		}, "LLM-Assistant-markers").start();
	}

	/** Direct action: applies the assistant's quick fixes in the target editor (no model call). */
	private void applyAllFixes() {
		markerAction(EditorMarkers::applyAll);
	}

	private void clearMarkers() {
		markerAction(ed -> {
			EditorMarkers.clear(ed);
			return "Markers removed from " + editors.labelOf(ed);
		});
	}

	private void testMarkers() {
		markerAction(ed -> "Test in " + editors.labelOf(ed) + ": " + EditorMarkers.selfTest(ed));
	}

	/** Direct action: compiles the target Java editor and marks the errors (no model call). */
	private void checkNow() {
		markerAction(ed -> {
			if(!"java".equals(EditorBridge.languageOf(ed)))
				return editors.labelOf(ed) + " is not a .java file. Macros can only be checked by running them, or use \"Find errors & add quick fixes\".";
			JavaRunner.Compiled c = JavaRunner.compile(editors.getText(ed));
			EditorMarkers.clear(ed);
			if(c.issues.isEmpty())
				return c.ok ? editors.labelOf(ed) + " compiles without problems." : "Compilation failed without line information:\n" + c.diagnostics;
			return EditorMarkers.mark(ed, c.issues, false);
		});
	}

	/** Locations of all copies of this plugin in the plugins folder (jars containing the plugin classes). */
	private static List<String> pluginCopies() {
		List<String> out = new ArrayList<>();
		String plugins = ij.Menus.getPlugInsPath();
		if(plugins != null)
			findCopies(new java.io.File(plugins), out, 0);
		return out;
	}

	private static void findCopies(java.io.File dir, List<String> out, int depth) {
		java.io.File[] files = dir.listFiles();
		if(files == null || depth > 4)
			return;
		for(java.io.File f : files) {
			if(f.isDirectory()) {
				if(new java.io.File(f, "llmassistant/ChatWindow.class").isFile())
					out.add(f.getAbsolutePath() + " (class folder)");
				findCopies(f, out, depth + 1);
			} else if(f.getName().toLowerCase().endsWith(".jar")) {
				try(java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
					if(z.getEntry("llmassistant/ChatWindow.class") != null)
						out.add(f.getAbsolutePath());
				} catch(Exception ignored) {
				}
			}
		}
	}

	private void runQuick(String prompt, boolean snapshot) {
		if(busy.get())
			return;
		if(prompt.equals(P_FIND) && !EditorMarkers.available()) {
			error(EditorMarkers.UNAVAILABLE + "\nReason: " + EditorMarkers.unavailableReason());
			return;
		}
		if(prompt.equals(P_FIND) && !settings.enableTools) {
			error("Finding errors with markers needs tool use: enable \"Let the model call SWTImageJ tools\" in General Preferences (\u2699 menu).");
			return;
		}
		/* the image analysis keeps a question the user already typed; the other actions replace the input */
		if(!snapshot || input.getText().isBlank())
			input.setText(prompt);
		forceSnapshot = snapshot;
		send();
	}

	private void dispose() {
		cancelled = true;
		client.cancel();
		if(activateFilter != null)
			display.removeFilter(SWT.Activate, activateFilter);
		editors.setTabListener(null);
		for(org.eclipse.swt.graphics.Resource r : new org.eclipse.swt.graphics.Resource[]{monoFont, boldFont, codeBg, dimFg, userFg, errorFg, gearIcon}) {
			if(r != null && !r.isDisposed())
				r.dispose();
		}
		if(instance == this)
			instance = null;
	}

	private void welcome() {
		boolean cloud = settings.baseUrl.contains("api.openai.com");
		String key = !settings.effectiveApiKey().isEmpty() ? settings.activeKeyLabel() : cloud ? "NOT configured - \u2699 > General Preferences..." : (settings.activeApiKey() != null ? settings.activeKeyLabel() + ", " : "") + "none (not needed for local servers)";
		appendLine("LLM Assistant " + LLM_Assistant.VERSION + " for SWTImageJ", boldFont, null, null);
		appendLine("Endpoint: " + settings.baseUrl + "   |   API key: " + key, null, dimFg, null);
		String where = editors.useExplorer() ? "the selected Script Explorer tab (new code opens in new tabs)" : "the selected editor";
		appendLine("The assistant can inspect the active image, read and write " + where + ", browse the scripts in the plugins/macros folders, look up menu commands and run macros" + (settings.confirmRun ? " (after your approval)." : "."), null, dimFg, null);
		appendLine("Documents: " + (settings.useBuiltinReference || !settings.documents.isEmpty() ? (KnowledgeBase.get().chunkCount() > 0 ? KnowledgeBase.get().summary() : "indexing in the background ...") : "none - add some with Documents...") + "", null, dimFg, null);
		if(EditorMarkers.available())
			appendLine("Editor markers: available" + (settings.markErrors ? "" : " (automatic error markers are off in General Preferences)") + " - Quick actions \u25be > Markers & quick fixes.", null, dimFg, null);
		else
			appendLine("Editor markers: NOT available in this SWTImageJ build (" + EditorMarkers.unavailableReason() + "). Update SWTImageJ to use markers and quick fixes.", null, errorFg, null);
		List<String> copies = pluginCopies();
		if(copies.size() > 1)
			appendLine("Warning: the plugin is installed " + copies.size() + " times - SWTImageJ may load an old copy. Keep only one and restart SWTImageJ:\n  " + String.join("\n  ", copies), null, errorFg, null);
		if(settings.enableVision)
			appendLine("Images: Attach \u25be > \"Attach snapshot\" or Quick actions \u25be > \"Analyze image\" let a vision model see the active image; the model can also open images and look at them itself.", null, dimFg, null);
		appendLine("", null, null, null);
	}

	/* ================================================================== transcript */

	private void appendLine(String text, Font font, Color fg, Color bg) {
		appendRaw(text + "\n", font, fg, bg);
	}

	private void appendRaw(String text, Font font, Color fg, Color bg) {
		if(transcript == null || transcript.isDisposed())
			return;
		int start = transcript.getCharCount();
		transcript.append(text);
		if(font != null || fg != null || bg != null) {
			StyleRange sr = new StyleRange(start, text.length(), fg, bg);
			sr.font = font;
			transcript.setStyleRange(sr);
		}
		transcript.setTopIndex(transcript.getLineCount() - 1);
	}

	/** Renders markdown-ish assistant text: fenced code in monospace on a tinted background. */
	private void appendAssistant(String content) {
		appendLine("Assistant:", boldFont, null, null);
		Matcher m = FENCE.matcher(content);
		int last = 0;
		while(m.find()) {
			appendRaw(content.substring(last, m.start()), null, null, null);
			String lang = m.group(1);
			String code = m.group(2);
			addCodeBlock(normalizeLang(lang), code);
			appendLine("[code #" + codeBlocks.size() + (lang.isEmpty() ? "" : " \u00b7 " + lang) + "]", null, dimFg, null);
			appendRaw(code.endsWith("\n") ? code : code + "\n", monoFont, null, codeBg);
			last = m.end();
		}
		appendRaw(content.substring(last), null, null, null);
		appendLine("\n", null, null, null);
	}

	private void ui(Runnable r) {
		if(display.isDisposed())
			return;
		display.asyncExec(() -> {
			if(shell != null && !shell.isDisposed())
				r.run();
		});
	}

	@Override
	public void info(String text) {
		ui(() -> appendLine("  \u2022 " + text, null, dimFg, null));
	}

	private void error(String text) {
		ui(() -> appendLine("Error: " + text + "\n", null, errorFg, null));
	}

	@Override
	public boolean confirm(String title, String message) {
		AtomicBoolean ok = new AtomicBoolean(false);
		display.syncExec(() -> {
			if(shell == null || shell.isDisposed())
				return;
			/*
			 * A turn that e.g. adds an overlay and then plots intensities can trigger this
			 * confirm() twice in a row. Without forcing the shell forward, a second prompt can
			 * open behind the chat (or behind a window a previous tool call just created, like
			 * the plot), leaving this background thread blocked in the syncExec above with no
			 * visible sign beyond the easy-to-miss busyIndicator phase text - looking exactly
			 * like a hang.
			 */
			shell.setMinimized(false);
			shell.forceActive();
			String before = busyIndicator.getPhase();
			busyIndicator.setPhase("Waiting for your approval...");
			MessageBox mb = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
			mb.setText(title);
			mb.setMessage(message);
			ok.set(mb.open() == SWT.YES);
			busyIndicator.setPhase(before);
		});
		return ok.get();
	}

	/* ================================================================== code blocks */

	private static String normalizeLang(String tag) {
		String t = tag == null ? "" : tag.toLowerCase();
		switch(t) {
			case "js":
			case "javascript":
				return "js";
			case "java":
				return "java";
			case "bsh":
			case "beanshell":
				return "bsh";
			case "py":
			case "python":
			case "jython":
				return "py";
			case "ijm":
			case "imagej":
			case "macro":
			case "ijmacro":
				return "ijm";
			default:
				return ""; // unknown, use the language chosen in the toolbar
		}
	}

	private void addCodeBlock(String lang, String code) {
		codeBlocks.add(new String[]{lang, code});
		String first = code.strip().split("\n", 2)[0];
		if(first.length() > 50)
			first = first.substring(0, 50) + "...";
		int lines = code.strip().split("\n").length;
		codeCombo.add("#" + codeBlocks.size() + " " + (lang.isEmpty() ? "code" : lang) + " (" + lines + " lines): " + first);
		codeCombo.select(codeCombo.getItemCount() - 1);
	}

	private void codeAction(String action) {
		int i = codeCombo.getSelectionIndex();
		if(i < 0 || i >= codeBlocks.size()) {
			status.setText("No code block yet.");
			return;
		}
		String lang = codeBlocks.get(i)[0].isEmpty() ? selectedLanguage() : codeBlocks.get(i)[0];
		String code = codeBlocks.get(i)[1];
		switch(action) {
			case "copy": {
				Clipboard cb = new Clipboard(display);
				cb.setContents(new Object[]{code}, new Transfer[]{TextTransfer.getInstance()});
				cb.dispose();
				status.setText("Copied code #" + (i + 1));
				break;
			}
			case "run":
				runUserCode(code, lang);
				break;
			default:
				new Thread(() -> {
					Editor ed = editors.target();
					if("new".equals(action) || ed == null) {
						String cls = "java".equals(lang) && JavaRunner.isCompleteClass(code) ? JavaRunner.className(code) : null;
						Editor n = editors.openNew(cls != null ? cls + ".java" : "LLM_Code_" + (i + 1) + EditorBridge.extensionFor(lang), code);
						info("Opened code #" + (i + 1) + " in a new " + (editors.isInExplorer(n) ? "Script Explorer tab" : "editor window"));
						ui(this::refreshEditors);
					} else if("insert".equals(action)) {
						editors.insertAtCaret(ed, code);
						info("Inserted code #" + (i + 1) + " into " + editors.labelOf(ed));
					} else {
						editors.replaceAll(ed, code);
						info("Replaced content of " + editors.labelOf(ed) + " with code #" + (i + 1));
					}
				}, "LLM-Assistant-editor").start();
		}
	}

	private void runUserCode(String code, String lang) {
		if(runningCode) {
			status.setText("Code is already running.");
			return;
		}
		appendLine("  \u2022 Running " + ImageJTools.languageName(lang) + " ...", null, dimFg, null);
		boolean ownSpinner = !busy.get();
		if(ownSpinner)
			busyIndicator.start("Running " + ImageJTools.languageName(lang) + "...");
		new Thread(() -> {
			runningCode = true;
			String report;
			try {
				report = ImageJTools.runCode(code, lang);
			} finally {
				runningCode = false;
			}
			final String r = report;
			ui(() -> {
				if(ownSpinner && !busy.get())
					busyIndicator.stop();
				appendLine(indent(r), monoFont, r.startsWith("FAILED") ? errorFg : dimFg, null);
				if(r.startsWith("FAILED") && input.getText().isBlank())
					input.setText("The code failed:\n" + r + "\nPlease fix it.");
			});
		}, "LLM-Assistant-run").start();
	}

	private static String indent(String s) {
		return "    " + s.strip().replace("\n", "\n    ");
	}

	/* ================================================================== combos */

	private void fillModels() {
		String cur = settings.model;
		modelCombo.removeAll();
		List<String> ms = new ArrayList<>(settings.models);
		if(!ms.contains(cur))
			ms.add(0, cur);
		for(String m : ms)
			modelCombo.add(m);
		modelCombo.setText(cur);
	}

	private void modelChanged() {
		String m = modelCombo.getText().trim();
		if(m.isEmpty())
			return;
		settings.model = m;
		settings.rememberModel(m);
		settings.save();
		status.setText("Model: " + m);
	}

	private void fetchModels() {
		status.setText("Fetching models ...");
		new Thread(() -> {
			try {
				List<String> ms = client.listModels();
				ui(() -> {
					settings.models.clear();
					settings.models.addAll(ms);
					settings.save();
					fillModels();
					status.setText(ms.size() + " models available");
				});
			} catch(Exception ex) {
				ui(() -> status.setText("Model list failed"));
				error(ex.getMessage());
			}
		}, "LLM-Assistant-models").start();
	}

	void refreshEditors() {
		if(editorCombo == null || editorCombo.isDisposed())
			return;
		int sel = editorCombo.getSelectionIndex();
		Editor selected = sel > 0 && sel - 1 < editorItems.size() ? editorItems.get(sel - 1) : null;
		editorItems.clear();
		editorItems.addAll(editors.listEditors());
		editorCombo.removeAll();
		editors.setSelected(null);
		Editor auto = editors.target();
		editorCombo.add("Auto" + (auto != null ? " (" + editors.labelOf(auto) + ")" : editors.useExplorer() ? " (new Script Explorer tab)" : ""));
		for(Editor e : editorItems)
			editorCombo.add(editors.labelOf(e));
		int idx = selected == null ? 0 : editorItems.indexOf(selected) + 1;
		editorCombo.select(Math.max(0, idx));
		editors.setSelected(idx > 0 ? selected : null);
	}

	private void editorChanged() {
		int i = editorCombo.getSelectionIndex();
		editors.setSelected(i > 0 && i - 1 < editorItems.size() ? editorItems.get(i - 1) : null);
	}

	private String selectedLanguage() {
		int i = langCombo.getSelectionIndex();
		return LANG_IDS[i < 0 ? 0 : i];
	}

	private void settingsChanged() {
		client = new OpenAIClient(settings);
		fillModels();
		attachImage.setSelection(settings.attachImageInfo);
		attachEditor.setSelection(settings.attachEditorText);
		attachSnapshot.setEnabled(settings.enableVision);
		attachSnapshot.setSelection(settings.attachSnapshot && settings.enableVision);
		updateAttachButton();
		updateNewButton();
		refreshEditors();
		status.setText("General Preferences saved");
	}

	/** Builds the document index in the background (first opening of the chat). */
	private void startIndexing() {
		KnowledgeBase kb = KnowledgeBase.get();
		if(kb.chunkCount() > 0 || kb.isIndexing() || (!settings.useBuiltinReference && settings.documents.isEmpty()))
			return;
		new Thread(() -> {
			kb.reindex(settings);
			info("Documents: " + kb.summary() + (settings.useBuiltinReference ? " (incl. the ImageJ macro functions reference)" : ""));
			ui(this::documentsChanged);
		}, "LLM-Assistant-index").start();
	}

	private void documentsChanged() {
		if(useDocs != null && !useDocs.isDisposed()) {
			useDocs.setSelection(settings.attachDocuments);
			useDocs.setToolTipText("Attach matching excerpts from: " + KnowledgeBase.get().summary());
			updateAttachButton();
		}
	}

	private void updateNewButton() {
		boolean ex = editors.useExplorer();
		newButton.setText(ex ? "New tab" : "New editor");
		newButton.setToolTipText(ex ? "Open the code in a new Script Explorer tab" : "Open the code in a new editor window");
		newButton.getParent().layout();
	}

	/* ================================================================== conversation */

	private void clear() {
		if(busy.get())
			return;
		history.clear();
		codeBlocks.clear();
		codeCombo.removeAll();
		transcript.setText(""); // completely empty: the start text is not shown again
		status.setText("");
		input.setFocus();
	}

	private void stop() {
		cancelled = true;
		client.cancel();
		if(runningCode)
			Interpreter.abort();
		busyIndicator.setPhase("Stopping...");
	}

	private void setBusy(boolean b) {
		busy.set(b);
		if(shell.isDisposed())
			return;
		sendButton.setEnabled(!b);
		stopButton.setEnabled(b);
		if(b)
			busyIndicator.start("Waiting for model...");
		else
			busyIndicator.stop();
		shell.setCursor(b ? display.getSystemCursor(SWT.CURSOR_APPSTARTING) : null);
	}

	/** Shows what the running request is doing (thread safe). */
	private void phase(String p) {
		ui(() -> busyIndicator.setPhase(p));
	}

	private String systemPrompt() {
		String lang = ImageJTools.languageName(selectedLanguage());
		StringBuilder sb = new StringBuilder();
		sb.append("You are an expert image-analysis assistant embedded in SWTImageJ, the Eclipse SWT port of ImageJ 1.x ");
		sb.append("(same ij.* Java API and macro language as ImageJ 1.x, but the GUI is SWT instead of AWT: windows are SWT Shells, ");
		sb.append("ij.plugin.frame.Editor holds an SWT StyledText, and GUI code must run on the SWT display thread via Display.getDefault().syncExec). ");
		sb.append("Help the user write, explain, debug and run ImageJ macros and scripts for the images currently open.\n\n");
		sb.append("Rules:\n");
		sb.append("- Preferred language for new code: ").append(lang).append(". Unless asked otherwise, write the ImageJ macro language (.ijm).\n");
		sb.append("- Put code in fenced blocks tagged with the language (```ijm, ```javascript, ```bsh, ```python).\n");
		sb.append("- Use only real ImageJ commands. When unsure about an exact command name for run(\"...\"), call search_commands first. ");
		sb.append("Option strings use the Macro Recorder syntax, e.g. run(\"Gaussian Blur...\", \"sigma=2\").\n");
		if(settings.enableTools) {
			sb.append("- You have tools to inspect the session (get_imagej_context, get_active_image_info), read/write the editor (get_editor_text, set_editor_text), ");
			sb.append("browse existing scripts (list_scripts, read_script, open_script) and execute code (run_code, run_editor). ");
			if(editors.useExplorer())
				sb.append("SWTImageJ's Script Explorer is the user's main workspace: a file tree of the plugins and macros folders with one editor tab per file. The target editor is its selected tab, and set_editor_text mode 'new' opens a new tab. Reuse or adapt existing scripts from these folders when they fit. ");
			sb.append("Inspect the image or editor instead of guessing. When the user asks to create or change a macro, write it into the editor with set_editor_text (mode 'new' for a new file, 'replace' to change the existing one). ");
			sb.append("You can open images (open_image: file path, URL or sample name from list_sample_images) and switch the active image (select_image). ");
			if(settings.enableVision)
				sb.append("You can SEE images: call view_image to receive a rendered snapshot of an open image (e.g. before choosing a threshold method, or to check a segmentation/mask result after running a macro). Images the user attaches appear in their message. Describe only what is actually visible; say so when a snapshot is too small or ambiguous. ");
			sb.append("Only run code when the user asked for it or it is clearly needed to verify a result; if a run fails, read the error, fix the code and try again.\n");
		} else {
			sb.append("- You cannot call tools; the user's messages may contain attached image info and editor code.\n");
		}
		if(settings.enableTools && EditorMarkers.available()) {
			sb.append("- Editor markers: the SWTImageJ editor shows error/warning/info markers (squiggly underline + ruler dot, message on hover) with optional quick fixes that the user applies by double-clicking. ");
			sb.append("When asked to find, check or explain errors in the editor code, mark them with mark_issues (after get_editor_text with line_numbers=true; for Java run check_code first) and give each fixable problem a 'replacement' with the corrected text of the marked range - prefer a small exact 'match' over whole lines. ");
			sb.append("run_editor and check_code mark compiler/runtime errors automatically; turn them into quick fixes with mark_issues. Only use apply_quick_fixes or set_editor_text for fixes when the user explicitly asks you to change the code.\n");
		}
		if(KnowledgeBase.get().chunkCount() > 0) {
			sb.append("- Reference documents are available (" + KnowledgeBase.get().summary() + "). Excerpts may be attached to user messages; ");
			sb.append(settings.enableTools ? "search more with search_documents (e.g. to check the exact signature of a macro function before using it). " : "");
			sb.append("Prefer documented facts over memory, and say which document you used. If the documents contradict your knowledge, follow the documents.\n");
		}
		sb.append("- Java is supported (language 'java', files *.java): run_code compiles it in memory with javac and returns compiler errors with line numbers and exceptions - fix and retry. ");
		sb.append("Write either statements only (wrapped into a PlugIn with common ij.* and java.util/java.io imports) for quick tasks, or a complete class implementing ij.plugin.PlugIn (run(String arg)) or ij.plugin.filter.PlugInFilter (setup/run(ImageProcessor)) for reusable plugins. ");
		sb.append("Use the ImageJ 1.x Java API (IJ, ImagePlus, ImageProcessor, WindowManager, ResultsTable, RoiManager, GenericDialog, Analyzer, ParticleAnalyzer). ");
		sb.append("Do not create AWT/Swing windows; for custom GUI use SWT on the display thread (Display.getDefault().syncExec) or GenericDialog. java.awt.Rectangle/Polygon/Color are fine as ImageJ API types. ");
		sb.append("A class that should appear in the Plugins menu needs an underscore in its name (e.g. Measure_Cells); install it with save_java_plugin when the user wants to keep it.\n");
		sb.append("- Prefer non-destructive processing (duplicate images before modifying them) and batch mode for loops over many images.\n");
		sb.append("- Be concise; explain the key steps and parameters the user may want to adjust.\n");
		if(settings.extraInstructions != null && !settings.extraInstructions.isBlank())
			sb.append("\nAdditional user instructions:\n").append(settings.extraInstructions.strip()).append('\n');
		return sb.toString();
	}

	private void send() {
		if(busy.get())
			return;
		String text = input.getText().strip();
		if(text.isEmpty())
			return;
		if(settings.effectiveApiKey().isEmpty() && settings.baseUrl.contains("api.openai.com")) {
			error("No API key configured for OpenAI: \u2699 > General Preferences... > API key > Add...");
			return;
		}
		String model = modelCombo.getText().trim().isEmpty() ? settings.model : modelCombo.getText().trim();
		if(!model.equals(settings.model))
			modelChanged();

		/* build the user message with optional context attachments */
		StringBuilder content = new StringBuilder(text);
		List<String> attached = new ArrayList<>();
		if(attachImage.getSelection()) {
			content.append("\n\n[SWTImageJ context]\n").append(tools.contextSummary());
			attached.add("image info");
		}
		if(attachEditor.getSelection()) {
			Editor ed = editors.target();
			if(ed != null) {
				String code = editors.getText(ed);
				String sel = editors.getSelection(ed);
				if(code != null && !code.isBlank()) {
					content.append("\n\n[").append(editors.labelOf(ed)).append("]\n```").append(EditorBridge.languageOf(ed)).append('\n').append(code.length() > 40000 ? code.substring(0, 40000) + "\n...[truncated]" : code).append("\n```\n");
					if(!sel.isBlank())
						content.append("[Selected in editor]\n```\n").append(sel).append("\n```\n");
					attached.add(editors.labelOf(ed));
				}
			}
		}
		/* document excerpts are retrieved on the background thread (hybrid search may call the embedding server) */
		final String docQuery = useDocs.getSelection() && KnowledgeBase.get().chunkCount() > 0 ? text : null;
		ImageJTools.Snapshot snap = null;
		boolean wantSnap = settings.enableVision && (forceSnapshot || attachSnapshot.getSelection());
		forceSnapshot = false;
		if(wantSnap) {
			ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
			if(imp == null) {
				error("No image is open - nothing to attach. Open an image or ask the assistant to open one.");
				return;
			}
			try {
				snap = ImageJTools.snapshot(imp, settings.snapshotMaxSize, false);
				attached.add("snapshot " + snap.describe());
			} catch(Exception ex) {
				error("Could not render the image: " + ex.getMessage());
				return;
			}
		}
		appendLine("You:", boldFont, userFg, null);
		appendLine(text, null, userFg, null);
		if(!attached.isEmpty())
			appendLine("  (attached: " + String.join(", ", attached) + ")", null, dimFg, null);
		appendLine("", null, null, null);
		input.setText("");
		if(snap != null)
			history.add(Json.obj("role", "user", "content", Json.arr(Json.obj("type", "text", "text", content.toString()), snap.contentPart())));
		else
			history.add(Json.obj("role", "user", "content", content.toString()));

		cancelled = false;
		setBusy(true);
		final String system = systemPrompt();
		new Thread(() -> conversationLoop(model, system, docQuery), "LLM-Assistant-chat").start();
	}

	/** Runs on a background thread: model call, tool calls, repeat until a final answer. */
	private void conversationLoop(String model, String system, String docQuery) {
		try {
			if(docQuery != null) {
				phase("Searching documents...");
				attachDocumentExcerpts(docQuery);
			}
			List<Object> toolDefs = settings.enableTools ? tools.definitions() : null;
			int rounds = Math.max(1, settings.maxToolRounds);
			for(int round = 0; round < rounds && !cancelled; round++) {
				List<Map<String, Object>> msgs = new ArrayList<>();
				msgs.add(Json.obj("role", "system", "content", system));
				synchronized(history) {
					msgs.addAll(pruneImages(history));
				}
				phase(round == 0 ? "Waiting for model..." : "Waiting for model (step " + (round + 1) + ")...");
				Map<String, Object> reply = client.chat(model, msgs, toolDefs);
				String content = reply.get("content") == null ? null : reply.get("content").toString();
				List<Object> calls = Json.asList(reply.get("tool_calls"));

				Map<String, Object> assistant = Json.obj("role", "assistant", "content", content);
				if(calls != null && !calls.isEmpty())
					assistant.put("tool_calls", calls);
				history.add(assistant);

				if(content != null && !content.isBlank()) {
					final String c = content;
					ui(() -> appendAssistant(c));
				}
				if(calls == null || calls.isEmpty())
					return; // final answer
				for(Object o : calls) {
					if(cancelled)
						break;
					Map<String, Object> call = Json.asMap(o);
					Map<String, Object> fn = Json.asMap(call.get("function"));
					String id = Json.str(call, "id", "");
					String name = Json.str(fn, "name", "");
					Map<String, Object> args;
					try {
						String a = Json.str(fn, "arguments", "{}");
						args = a.isBlank() ? Json.obj() : Json.asMap(Json.parse(a));
					} catch(Exception ex) {
						args = null;
					}
					String result;
					if(args == null) {
						result = "ERROR: could not parse the tool arguments as JSON.";
					} else {
						info("tool: " + name + describeArgs(args));
						phase("Running tool: " + name + "...");
						captureCode(name, args);
						runningCode = name.startsWith("run_");
						try {
							result = tools.execute(name, args);
						} finally {
							runningCode = false;
						}
						String firstLine = result.strip().split("\n", 2)[0];
						info("  \u2192 " + (firstLine.length() > 120 ? firstLine.substring(0, 120) + "..." : firstLine));
					}
					history.add(Json.obj("role", "tool", "tool_call_id", id, "content", result));
				}
				// answer every tool call even when cancelled, so the history stays valid
				if(cancelled)
					repairPendingToolCalls();
				// images requested via view_image: tool messages can only carry text, so send them as a user message
				List<ImageJTools.Snapshot> snaps = tools.drainSnapshots();
				if(!snaps.isEmpty() && !cancelled) {
					List<Object> parts = new ArrayList<>();
					StringBuilder d = new StringBuilder("[Snapshot(s) requested with view_image]");
					for(ImageJTools.Snapshot sn : snaps)
						d.append("\n- ").append(sn.describe());
					parts.add(Json.obj("type", "text", "text", d.toString()));
					for(ImageJTools.Snapshot sn : snaps)
						parts.add(sn.contentPart());
					history.add(Json.obj("role", "user", "content", parts));
				}
				if(round == rounds - 1)
					info("Stopped after " + rounds + " tool rounds (see General Preferences). Send a message to continue.");
			}
		} catch(InterruptedException ex) {
			info("Cancelled.");
		} catch(OpenAIClient.ApiException ex) {
			String m = ex.getMessage();
			if(historyHasImages() && m != null && m.toLowerCase().matches(".*(image|vision|multimodal|content type|image_url|unsupported).*"))
				m += "\nThe model may not support images: choose a vision-capable model, or switch off Attach \u25be > \"Attach snapshot\" and click Clear.";
			error(m);
		} catch(Throwable t) {
			error(t.getClass().getSimpleName() + ": " + t.getMessage());
			IJ.handleException(t);
		} finally {
			repairPendingToolCalls();
			ui(() -> {
				setBusy(false);
				refreshEditors();
			});
		}
	}

	/** Appends the best document excerpts to the last user message in the history. */
	@SuppressWarnings("unchecked")
	private void attachDocumentExcerpts(String query) {
		KnowledgeBase kb = KnowledgeBase.get();
		List<String> docs = new ArrayList<>();
		String ex = kb.excerpts(query, settings.docTopK, settings.docMaxChars, docs);
		if(ex.isEmpty())
			return;
		String add = "\n\n[Reference excerpts from the user's documents - use them when relevant and mention which document you relied on]\n" + ex;
		synchronized(history) {
			for(int i = history.size() - 1; i >= 0; i--) {
				Map<String, Object> m = history.get(i);
				if(!"user".equals(m.get("role")))
					continue;
				Object c = m.get("content");
				if(c instanceof String)
					m.put("content", c + add);
				else if(c instanceof List) {
					for(Object part : (List<Object>)c) {
						Map<String, Object> pm = Json.asMap(part);
						if(pm != null && "text".equals(pm.get("type"))) {
							pm.put("text", pm.get("text") + add);
							break;
						}
					}
				}
				break;
			}
		}
		int n = ex.startsWith("--- [") ? ex.split("\n--- ").length : 0;
		info("Documents: " + n + " excerpt(s) from " + String.join(", ", docs) + (kb.semanticReady() ? " (hybrid search)" : " (keyword search)"));
	}

	private boolean historyHasImages() {
		synchronized(history) {
			for(Map<String, Object> m : history)
				if(m.get("content") instanceof List)
					return true;
		}
		return false;
	}

	/**
	 * Copy of the history in which only the newest KEEP_IMAGES messages keep their
	 * pictures; older image parts are replaced by a short text, so long
	 * conversations don't re-send every image with each request.
	 */
	private static List<Map<String, Object>> pruneImages(List<Map<String, Object>> hist) {
		List<Map<String, Object>> out = new ArrayList<>(hist);
		int kept = 0;
		for(int i = out.size() - 1; i >= 0; i--) {
			Map<String, Object> m = out.get(i);
			List<Object> parts = Json.asList(m.get("content"));
			if(parts == null)
				continue;
			if(kept < KEEP_IMAGES) {
				kept++;
				continue;
			}
			StringBuilder text = new StringBuilder();
			int images = 0;
			for(Object p : parts) {
				Map<String, Object> pm = Json.asMap(p);
				if("text".equals(Json.str(pm, "type", "")))
					text.append(Json.str(pm, "text", "")).append('\n');
				else
					images++;
			}
			text.append("[").append(images).append(" earlier image(s) omitted - call view_image again if needed]");
			Map<String, Object> copy = new java.util.LinkedHashMap<>(m);
			copy.put("content", text.toString());
			out.set(i, copy);
		}
		return out;
	}

	/** Adds "cancelled" results for tool calls that never got an answer. */
	private void repairPendingToolCalls() {
		synchronized(history) {
			for(int i = history.size() - 1; i >= 0; i--) {
				Map<String, Object> m = history.get(i);
				if(!"assistant".equals(m.get("role")))
					continue;
				List<Object> calls = Json.asList(m.get("tool_calls"));
				if(calls == null)
					return;
				java.util.Set<String> answered = new java.util.HashSet<>();
				for(int j = i + 1; j < history.size(); j++)
					answered.add(Json.str(history.get(j), "tool_call_id", ""));
				for(Object o : calls) {
					String id = Json.str(Json.asMap(o), "id", "");
					if(!answered.contains(id))
						history.add(Json.obj("role", "tool", "tool_call_id", id, "content", "CANCELLED by the user."));
				}
				return;
			}
		}
	}

	private static String describeArgs(Map<String, Object> args) {
		if(args.isEmpty())
			return "()";
		StringBuilder sb = new StringBuilder("(");
		for(Map.Entry<String, Object> e : args.entrySet()) {
			String v = String.valueOf(e.getValue());
			if(e.getKey().equals("code"))
				v = v.split("\n").length + " lines";
			else if(v.length() > 40)
				v = v.substring(0, 40) + "...";
			sb.append(sb.length() > 1 ? ", " : "").append(e.getKey()).append('=').append(v);
		}
		return sb.append(')').toString();
	}

	/** Makes code the model writes/runs through tools available in the code combo, too. */
	private void captureCode(String tool, Map<String, Object> args) {
		if(!(tool.equals("set_editor_text") || tool.equals("run_code")))
			return;
		String code = Json.str(args, "code", "");
		if(code.isBlank())
			return;
		String lang = tool.equals("run_code") ? normalizeLang(Json.str(args, "language", "ijm")) : normalizeLang(extOf(Json.str(args, "title", "")));
		ui(() -> addCodeBlock(lang, code));
	}

	private static String extOf(String title) {
		int i = title == null ? -1 : title.lastIndexOf('.');
		return i < 0 ? "" : title.substring(i + 1);
	}

	/** For programmatic use, e.g. from a macro: call("llmassistant.ChatWindow.ask", "question"). */
	public static String ask(String question) {
		showWindow();
		Display.getDefault().asyncExec(() -> {
			if(instance != null && !instance.shell.isDisposed()) {
				instance.input.setText(question);
				instance.send();
			}
		});
		return "";
	}
}
