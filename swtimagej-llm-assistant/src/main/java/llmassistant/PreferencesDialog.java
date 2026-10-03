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

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Preferences for the LLM connection: API key, endpoint, model and behaviour.
 * Must be opened on the SWT display thread (use {@link #open(Shell, Runnable)}).
 */
public class PreferencesDialog {

	private final LLMSettings s = LLMSettings.get();
	private Shell shell;
	private Text urlText, tempText, timeoutText, roundsText, extraText, snapText;
	private Combo modelCombo, langCombo, keyCombo;
	private Label keyInfo;
	/** Working copy of the keys; committed to the settings on Save / Test connection. */
	private final java.util.List<LLMSettings.ApiKey> keys = new java.util.ArrayList<>();
	private String activeKey;
	private Button useExplorer, markErrors, enableVision, attachSnap, enableTools, confirmRun, confirmReplace, attachImage, attachEditor;
	private Label status;
	private final Runnable onSave;

	private static final String[] LANG_IDS = {"ijm", "java", "js", "bsh", "py"};
	private static final String[] LANG_NAMES = {"ImageJ macro (.ijm)", "Java (.java)", "JavaScript (.js)", "BeanShell (.bsh)", "Python/Jython (.py)"};

	private PreferencesDialog(Runnable onSave) {
		this.onSave = onSave;
	}

	/** Opens the dialog (thread safe). onSave is called on the UI thread after saving. */
	public static void open(Shell parent, Runnable onSave) {
		Display d = Display.getDefault();
		d.asyncExec(() -> new PreferencesDialog(onSave).create(parent));
	}

	private void create(Shell parent) {
		Display d = Display.getDefault();
		shell = parent != null && !parent.isDisposed() ? new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL) : new Shell(d, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText("LLM Assistant - General Preferences");
		shell.setLayout(new GridLayout(1, false));

		/* --- connection --- */
		Group con = group("Connection (OpenAI or any OpenAI-compatible endpoint)", 3);
		for(LLMSettings.ApiKey k : s.apiKeys)
			keys.add(k.copy());
		LLMSettings.ApiKey act = s.activeApiKey();
		activeKey = act != null ? act.name : (LLMSettings.environmentKey().isEmpty() ? "" : LLMSettings.ENV_KEY);
		label(con, "API key:");
		Composite keyRow = new Composite(con, SWT.NONE);
		keyRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		GridLayout kl = new GridLayout(4, false);
		kl.marginWidth = kl.marginHeight = 0;
		keyRow.setLayout(kl);
		keyCombo = new Combo(keyRow, SWT.READ_ONLY);
		keyCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		keyCombo.setToolTipText("The key used for all requests. Keys with their own server switch the Base URL, too.");
		keyCombo.addListener(SWT.Selection, _ -> keySelected());
		Button addKey = new Button(keyRow, SWT.PUSH);
		addKey.setText("Add...");
		addKey.addListener(SWT.Selection, _ -> editKey(null));
		Button editKey = new Button(keyRow, SWT.PUSH);
		editKey.setText("Edit...");
		editKey.addListener(SWT.Selection, _ -> editKey(selectedKey()));
		Button removeKey = new Button(keyRow, SWT.PUSH);
		removeKey.setText("Remove");
		removeKey.addListener(SWT.Selection, _ -> removeKey());
		new Label(con, SWT.NONE);
		keyInfo = new Label(con, SWT.NONE);
		keyInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

		label(con, "Base URL:");
		urlText = new Text(con, SWT.BORDER);
		urlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		urlText.setText(s.baseUrl);
		urlText.setToolTipText("e.g. https://api.openai.com/v1, http://127.0.0.1:1234/v1 (LM Studio), http://localhost:11434/v1 (Ollama). /v1 is added automatically if the URL has no path.");
		fillKeys();

		label(con, "Default model:");
		modelCombo = new Combo(con, SWT.DROP_DOWN);
		modelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		for(String m : s.models)
			modelCombo.add(m);
		modelCombo.setText(s.model);
		Button fetch = new Button(con, SWT.PUSH);
		fetch.setText("Fetch models");
		fetch.addListener(SWT.Selection, _ -> fetchModels(false));

		Label keyNote = new Label(con, SWT.WRAP);
		keyNote.setText("Keys are stored in " + LLMSettings.settingsFile().getAbsolutePath() + " (owner read/write only where supported). Local servers (LM Studio, Ollama) need no key. The OPENAI_API_KEY environment variable can be selected, too.");
		GridData kn = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
		kn.widthHint = 520;
		keyNote.setLayoutData(kn);

		/* --- generation --- */
		Group gen = group("Generation", 4);
		label(gen, "Temperature:");
		tempText = new Text(gen, SWT.BORDER);
		tempText.setText(s.temperature == null ? "" : s.temperature);
		tempText.setMessage("server default");
		tempText.setLayoutData(new GridData(80, SWT.DEFAULT));
		label(gen, "Timeout (s):");
		timeoutText = new Text(gen, SWT.BORDER);
		timeoutText.setText("" + s.timeoutSeconds);
		timeoutText.setLayoutData(new GridData(80, SWT.DEFAULT));
		label(gen, "Max tool rounds:");
		roundsText = new Text(gen, SWT.BORDER);
		roundsText.setText("" + s.maxToolRounds);
		roundsText.setLayoutData(new GridData(80, SWT.DEFAULT));
		label(gen, "Default language:");
		langCombo = new Combo(gen, SWT.READ_ONLY);
		langCombo.setItems(LANG_NAMES);
		int li = java.util.Arrays.asList(LANG_IDS).indexOf(s.defaultLanguage);
		langCombo.select(li < 0 ? 0 : li);

		/* --- behaviour --- */
		Group beh = group("SWTImageJ integration", 1);
		useExplorer = check(beh, "Use the Script Explorer by default (read the selected tab, open new code in new tabs; opened when needed)", s.useScriptExplorer);
		markErrors = check(beh, "Show errors from runs and checks as markers in the editor (quick fixes by double-click)", s.markErrors);
		if(!EditorMarkers.available()) {
			markErrors.setEnabled(false);
			markErrors.setToolTipText(EditorMarkers.UNAVAILABLE);
		}
		if(!EditorBridge.explorerAvailable()) {
			useExplorer.setEnabled(false);
			useExplorer.setToolTipText("This SWTImageJ version has no Script Explorer - standalone editors are used.");
		}
		enableTools = check(beh, "Let the model call SWTImageJ tools (read images/editor, write editor, run code)", s.enableTools);
		confirmRun = check(beh, "Ask before the model runs macros or scripts", s.confirmRun);
		confirmReplace = check(beh, "Ask before the model replaces the whole editor content", s.confirmEditorReplace);
		attachImage = check(beh, "Attach active image info to messages by default", s.attachImageInfo);
		attachEditor = check(beh, "Attach target editor content to messages by default", s.attachEditorText);

		Group vis = group("Images (vision)", 3);
		enableVision = check(vis, "Allow sending image snapshots to the model (needs a vision-capable model)", s.enableVision);
		enableVision.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		attachSnap = check(vis, "Attach a snapshot of the active image to messages by default", s.attachSnapshot);
		attachSnap.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		label(vis, "Max. snapshot size (px):");
		snapText = new Text(vis, SWT.BORDER);
		snapText.setText("" + s.snapshotMaxSize);
		snapText.setLayoutData(new GridData(80, SWT.DEFAULT));
		label(vis, "longest side; larger images are scaled down");

		Group ex = group("Additional instructions for the model (optional)", 1);
		extraText = new Text(ex, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		GridData eg = new GridData(SWT.FILL, SWT.FILL, true, true);
		eg.heightHint = 70;
		extraText.setLayoutData(eg);
		extraText.setText(s.extraInstructions == null ? "" : s.extraInstructions);
		extraText.setMessage("e.g. \"Always use batch mode\" or \"Comment the code in German\"");

		/* --- buttons --- */
		Composite bar = new Composite(shell, SWT.NONE);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		bar.setLayout(new GridLayout(4, false));
		status = new Label(bar, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button test = new Button(bar, SWT.PUSH);
		test.setText("Test connection");
		test.addListener(SWT.Selection, _ -> fetchModels(true));
		Button cancel = new Button(bar, SWT.PUSH);
		cancel.setText("Cancel");
		cancel.addListener(SWT.Selection, _ -> shell.close());
		Button save = new Button(bar, SWT.PUSH);
		save.setText("Save");
		save.addListener(SWT.Selection, _ -> save());
		shell.setDefaultButton(save);

		shell.pack();
		org.eclipse.swt.graphics.Point size = shell.getSize();
		shell.setSize(Math.max(620, size.x), size.y);
		center(shell, parent);
		shell.open();
	}

	private Group group(String title, int cols) {
		Group g = new Group(shell, SWT.NONE);
		g.setText(title);
		g.setLayout(new GridLayout(cols, false));
		g.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, cols == 1 && title.startsWith("Additional")));
		return g;
	}

	private static void label(Composite c, String text) {
		new Label(c, SWT.NONE).setText(text);
	}

	private static Button check(Composite c, String text, boolean value) {
		Button b = new Button(c, SWT.CHECK);
		b.setText(text);
		b.setSelection(value);
		return b;
	}

	/* ================================================================ API keys */

	private void fillKeys() {
		keyCombo.removeAll();
		for(LLMSettings.ApiKey k : keys)
			keyCombo.add(k.name);
		if(!LLMSettings.environmentKey().isEmpty())
			keyCombo.add(LLMSettings.ENV_KEY);
		if(keyCombo.getItemCount() == 0) {
			keyCombo.add("(no key - click Add...)");
			keyCombo.select(0);
			keyCombo.setData(null);
			keyInfo.setText("No key stored. Needed for OpenAI and other cloud services.");
			return;
		}
		int idx = keyCombo.indexOf(activeKey == null ? "" : activeKey);
		keyCombo.select(Math.max(0, idx));
		keyCombo.setData(Boolean.TRUE);
		updateKeyInfo();
	}

	private LLMSettings.ApiKey selectedKey() {
		if(keyCombo.getData() == null)
			return null;
		String name = keyCombo.getText();
		for(LLMSettings.ApiKey k : keys)
			if(k.name.equals(name))
				return k;
		return null;
	}

	private void keySelected() {
		if(keyCombo.getData() == null)
			return;
		activeKey = keyCombo.getText();
		LLMSettings.ApiKey k = selectedKey();
		if(k != null && !k.baseUrl.isBlank())
			urlText.setText(k.baseUrl);
		updateKeyInfo();
	}

	private void updateKeyInfo() {
		LLMSettings.ApiKey k = selectedKey();
		if(k != null)
			keyInfo.setText((k.key.isBlank() ? "No key (fine for LM Studio and other local servers)" : "Key " + k.masked()) + (k.baseUrl.isBlank() ? " - uses the Base URL below" : " - server " + k.baseUrl));
		else if(LLMSettings.ENV_KEY.equals(keyCombo.getText()))
			keyInfo.setText("Key " + new LLMSettings.ApiKey("", LLMSettings.environmentKey(), "").masked() + " from the environment");
		keyInfo.getParent().layout();
	}

	private void removeKey() {
		LLMSettings.ApiKey k = selectedKey();
		if(k == null)
			return;
		MessageBox mb = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		mb.setText("Remove API key");
		mb.setMessage("Remove the key \"" + k.name + "\" (" + k.masked() + ")?");
		if(mb.open() != SWT.YES)
			return;
		keys.remove(k);
		activeKey = keys.isEmpty() ? (LLMSettings.environmentKey().isEmpty() ? "" : LLMSettings.ENV_KEY) : keys.get(0).name;
		fillKeys();
	}

	/** Dialog to add (k == null) or edit a key: name, key, optional server. */
	private void editKey(LLMSettings.ApiKey k) {
		Shell d = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		d.setText(k == null ? "Add API key" : "Edit API key");
		d.setLayout(new GridLayout(3, false));
		label(d, "Name:");
		Text name = new Text(d, SWT.BORDER);
		name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		name.setMessage("e.g. OpenAI lab, OpenRouter, LM Studio");
		label(d, "API key:");
		Text[] key = {new Text(d, SWT.BORDER | SWT.PASSWORD)};
		GridData kg = new GridData(SWT.FILL, SWT.CENTER, true, false);
		kg.widthHint = 320;
		key[0].setLayoutData(kg);
		key[0].setMessage("sk-...  (leave empty for LM Studio, Ollama and other local servers)");
		Button show = new Button(d, SWT.CHECK);
		show.setText("Show");
		label(d, "Server (optional):");
		Text url = new Text(d, SWT.BORDER);
		url.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		url.setMessage("Base URL used with this key, e.g. https://openrouter.ai/api/v1 - empty = keep current");
		Label err = new Label(d, SWT.NONE);
		err.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		if(k != null) {
			name.setText(k.name);
			key[0].setText(k.key);
			url.setText(k.baseUrl);
		}
		show.addListener(SWT.Selection, _ -> { // SWT.PASSWORD cannot be toggled: recreate the field
			Text n = new Text(d, SWT.BORDER | (show.getSelection() ? 0 : SWT.PASSWORD));
			n.setLayoutData(key[0].getLayoutData());
			n.setText(key[0].getText());
			n.setMessage("sk-...  (leave empty for LM Studio, Ollama and other local servers)");
			n.moveAbove(key[0]);
			key[0].dispose();
			key[0] = n;
			d.layout(true);
		});
		Composite b = new Composite(d, SWT.NONE);
		b.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 3, 1));
		b.setLayout(new GridLayout(2, true));
		Button cancel = new Button(b, SWT.PUSH);
		cancel.setText("Cancel");
		cancel.addListener(SWT.Selection, _ -> d.close());
		Button ok = new Button(b, SWT.PUSH);
		ok.setText("OK");
		ok.addListener(SWT.Selection, _ -> {
			String n = name.getText().replaceAll("[\\t\\r\\n]", " ").trim();
			String kv = key[0].getText().trim();
			if(n.isEmpty() || n.equals(LLMSettings.ENV_KEY)) {
				err.setText("Please enter a name.");
				return;
			}
			for(LLMSettings.ApiKey o : keys)
				if(o != k && o.name.equals(n)) {
					err.setText("A key named \"" + n + "\" exists already.");
					return;
				}
			String u = url.getText().trim().isEmpty() ? "" : OpenAIClient.normalizeBaseUrl(url.getText());
			if(k == null)
				keys.add(new LLMSettings.ApiKey(n, kv, u));
			else {
				k.name = n;
				k.key = kv;
				k.baseUrl = u;
			}
			activeKey = n; // a new or edited key becomes the current one
			if(!u.isEmpty())
				urlText.setText(u);
			d.close();
			fillKeys();
		});
		d.setDefaultButton(ok);
		d.pack();
		center(d, shell);
		d.open();
		name.setFocus();
	}

	/** Applies the dialog values to the settings object (without saving to disk). */
	private void apply() {
		s.apiKeys.clear();
		for(LLMSettings.ApiKey k : keys)
			s.apiKeys.add(k.copy());
		s.activeKey = activeKey == null ? "" : activeKey;
		s.baseUrl = OpenAIClient.normalizeBaseUrl(urlText.getText());
		urlText.setText(s.baseUrl);
		s.model = modelCombo.getText().trim().isEmpty() ? LLMSettings.DEFAULT_MODEL : modelCombo.getText().trim();
		s.rememberModel(s.model);
		s.temperature = tempText.getText().trim();
		s.timeoutSeconds = parse(timeoutText.getText(), s.timeoutSeconds);
		s.maxToolRounds = Math.max(1, parse(roundsText.getText(), s.maxToolRounds));
		s.defaultLanguage = LANG_IDS[Math.max(0, langCombo.getSelectionIndex())];
		s.useScriptExplorer = useExplorer.getSelection();
		s.markErrors = markErrors.getSelection();
		s.enableTools = enableTools.getSelection();
		s.enableVision = enableVision.getSelection();
		s.attachSnapshot = attachSnap.getSelection();
		s.snapshotMaxSize = Math.max(64, parse(snapText.getText(), s.snapshotMaxSize));
		s.confirmRun = confirmRun.getSelection();
		s.confirmEditorReplace = confirmReplace.getSelection();
		s.attachImageInfo = attachImage.getSelection();
		s.attachEditorText = attachEditor.getSelection();
		s.extraInstructions = extraText.getText();
	}

	private void save() {
		if(!tempText.getText().isBlank() && s.temperatureValueOf(tempText.getText()) == null) {
			status.setText("Temperature must be a number (or empty).");
			return;
		}
		apply();
		s.save();
		shell.close();
		if(onSave != null)
			onSave.run();
	}

	private void fetchModels(boolean testOnly) {
		apply();
		status.setText("Contacting " + s.baseUrl + " ...");
		Display d = shell.getDisplay();
		new Thread(() -> {
			String msg;
			List<String> models = null;
			try {
				models = new OpenAIClient(s).listModels();
				msg = "Connection OK - " + models.size() + " models available.";
			} catch(Exception ex) {
				msg = "Failed: " + ex.getMessage();
			}
			final List<String> fm = models;
			final String fmsg = msg;
			d.asyncExec(() -> {
				if(shell.isDisposed())
					return;
				status.setText(fmsg.length() > 90 ? fmsg.substring(0, 90) + "..." : fmsg);
				status.setToolTipText(fmsg);
				status.getParent().layout();
				if(fm != null) {
					String cur = modelCombo.getText();
					modelCombo.removeAll();
					for(String m : fm)
						modelCombo.add(m);
					modelCombo.setText(cur);
					s.models.clear();
					s.models.addAll(fm);
				} else if(testOnly) {
					MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
					mb.setText("Connection test");
					mb.setMessage(fmsg);
					mb.open();
				}
			});
		}, "LLM-Assistant-models").start();
	}

	private static int parse(String t, int def) {
		try {
			return Integer.parseInt(t.trim());
		} catch(Exception e) {
			return def;
		}
	}

	static void center(Shell sh, Shell parent) {
		org.eclipse.swt.graphics.Rectangle area = parent != null && !parent.isDisposed() ? parent.getBounds() : sh.getDisplay().getPrimaryMonitor().getClientArea();
		org.eclipse.swt.graphics.Point sz = sh.getSize();
		sh.setLocation(area.x + Math.max(0, (area.width - sz.x) / 2), area.y + Math.max(0, (area.height - sz.y) / 2));
	}
}
