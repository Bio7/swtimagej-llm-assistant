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
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Manages the reference documents: add files/folders, remove, re-index, and a
 * test search that shows which excerpts a question would retrieve.
 */
public class DocumentsDialog {

	private static DocumentsDialog open;
	private final LLMSettings s = LLMSettings.get();
	private final KnowledgeBase kb = KnowledgeBase.get();
	private final Runnable onChange;
	private Shell shell;
	private Table table;
	private Label status;
	private Button builtin, attach;
	private Text topK, maxChars, query, results, embedUrl, embedDims;
	private Button semantic;
	private org.eclipse.swt.widgets.Combo embedModel;
	private Label semanticStatus;
	private Text pathText;
	private int shownVersion = -1;

	private DocumentsDialog(Runnable onChange) {
		this.onChange = onChange;
	}

	/** Opens the dialog (thread safe); onChange runs on the UI thread after the index changed. */
	public static void show(Shell parent, Runnable onChange) {
		Display.getDefault().asyncExec(() -> {
			if(open != null && open.shell != null && !open.shell.isDisposed()) {
				open.shell.forceActive();
				return;
			}
			open = new DocumentsDialog(onChange);
			open.create(parent);
		});
	}

	private void create(Shell parent) {
		shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText("LLM Assistant - Reference documents");
		shell.setLayout(new GridLayout(1, false));

		Label intro = new Label(shell, SWT.WRAP);
		intro.setText("Documents improve the answers: the best-matching excerpts are attached to your messages, and the model can search them itself. Supported: .txt .md .html .csv .json .xml, code (.ijm .java .py .js .bsh .groovy .r), Word .docx, OpenDocument .odt, and .pdf (needs pdfbox-app-*.jar in plugins/jars).");
		GridData ig = new GridData(SWT.FILL, SWT.CENTER, true, false);
		ig.widthHint = 640;
		intro.setLayoutData(ig);

		table = new Table(shell, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION);
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		GridData tg = new GridData(SWT.FILL, SWT.FILL, true, true);
		tg.heightHint = 200;
		table.setLayoutData(tg);
		for(String[] c : new String[][]{{"Document", "260"}, {"Type", "60"}, {"Excerpts", "70"}, {"Status", "260"}}) {
			TableColumn col = new TableColumn(table, SWT.LEFT);
			col.setText(c[0]);
			col.setWidth(Integer.parseInt(c[1]));
		}

		Composite bar = new Composite(shell, SWT.NONE);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		bar.setLayout(new GridLayout(5, false));
		button(bar, "Add files...", this::addFiles);
		button(bar, "Add folder...", this::addFolder);
		button(bar, "Remove", this::remove);
		button(bar, "Re-index", this::reindex);
		status = new Label(bar, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		/* fallback without native dialogs: paste a path; files and folders can also be dropped onto the list */
		Composite pathBar = new Composite(shell, SWT.NONE);
		pathBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		GridLayout pl = new GridLayout(3, false);
		pl.marginHeight = 0;
		pathBar.setLayout(pl);
		new Label(pathBar, SWT.NONE).setText("Path:");
		pathText = new Text(pathBar, SWT.BORDER);
		pathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		pathText.setMessage("paste a file or folder path (or drag files and folders onto the list above)");
		pathText.addListener(SWT.DefaultSelection, _ -> addPath());
		button(pathBar, "Add", this::addPath);
		installDrop(table);

		Group opt = new Group(shell, SWT.NONE);
		opt.setText("Options");
		opt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		opt.setLayout(new GridLayout(4, false));
		builtin = check(opt, "Include the ImageJ macro functions reference (built into SWTImageJ)", s.useBuiltinReference);
		builtin.addListener(SWT.Selection, _ -> {
			s.useBuiltinReference = builtin.getSelection();
			s.save();
			reindex();
		});
		attach = check(opt, "Attach matching excerpts to each message automatically", s.attachDocuments);
		attach.addListener(SWT.Selection, _ -> {
			s.attachDocuments = attach.getSelection();
			s.save();
			if(onChange != null)
				onChange.run();
		});
		new Label(opt, SWT.NONE).setText("Excerpts per message:");
		topK = new Text(opt, SWT.BORDER);
		topK.setText("" + s.docTopK);
		topK.setLayoutData(new GridData(50, SWT.DEFAULT));
		new Label(opt, SWT.NONE).setText("Max. characters:");
		maxChars = new Text(opt, SWT.BORDER);
		maxChars.setText("" + s.docMaxChars);
		maxChars.setLayoutData(new GridData(70, SWT.DEFAULT));
		topK.addListener(SWT.Modify, _ -> saveNumbers());
		maxChars.addListener(SWT.Modify, _ -> saveNumbers());

		Group sem = new Group(shell, SWT.NONE);
		sem.setText("Semantic search (embeddings) - optional");
		sem.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		sem.setLayout(new GridLayout(4, false));
		semantic = check(sem, "Hybrid search: combine keyword search with embedding similarity (finds paraphrases, synonyms and other languages)", s.semanticSearch);
		new Label(sem, SWT.NONE).setText("Embedding model:");
		embedModel = new org.eclipse.swt.widgets.Combo(sem, SWT.DROP_DOWN);
		embedModel.setItems(new String[]{"text-embedding-3-small", "text-embedding-3-large", "text-embedding-nomic-embed-text-v1.5"});
		embedModel.setText(s.embeddingModel);
		embedModel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		embedModel.setToolTipText("OpenAI: text-embedding-3-small. LM Studio: load an embedding model and use its id (Fetch lists them).");
		Button fetch = new Button(sem, SWT.PUSH);
		fetch.setText("Fetch");
		fetch.setToolTipText("List the embedding models of the server");
		fetch.addListener(SWT.Selection, _ -> fetchEmbeddingModels());
		new Label(sem, SWT.NONE);
		new Label(sem, SWT.NONE).setText("Embedding server:");
		embedUrl = new Text(sem, SWT.BORDER);
		embedUrl.setText(s.embeddingBaseUrl == null ? "" : s.embeddingBaseUrl);
		embedUrl.setMessage("empty = same server as the chat (" + s.baseUrl + ")");
		embedUrl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		new Label(sem, SWT.NONE).setText("Dimensions:");
		embedDims = new Text(sem, SWT.BORDER);
		embedDims.setText("" + s.embeddingDimensions);
		embedDims.setToolTipText("Vector size for OpenAI text-embedding-3 models (other models use their own size)");
		embedDims.setLayoutData(new GridData(50, SWT.DEFAULT));
		semanticStatus = new Label(sem, SWT.WRAP);
		semanticStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		Button build = new Button(sem, SWT.PUSH);
		build.setText("Apply && build");
		build.setToolTipText("Save the embedding settings and (re)build the index; unchanged excerpts come from the cache");
		build.addListener(SWT.Selection, _ -> applySemantic());
		semantic.addListener(SWT.Selection, _ -> applySemantic());

		Group test = new Group(shell, SWT.NONE);
		test.setText("Test search - what would be attached for this question?");
		test.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		test.setLayout(new GridLayout(2, false));
		query = new Text(test, SWT.BORDER | SWT.SEARCH);
		query.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		query.setMessage("e.g. how do I threshold with Otsu and count particles");
		query.addListener(SWT.DefaultSelection, _ -> testSearch());
		button(test, "Search", this::testSearch);
		results = new Text(test, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
		GridData rg = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
		rg.heightHint = 160;
		results.setLayoutData(rg);

		Button close = new Button(shell, SWT.PUSH);
		close.setText("Close");
		close.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		close.addListener(SWT.Selection, _ -> shell.close());

		fill();
		poll();
		/* opened from the Plugins menu without the chat: build the index now */
		if(kb.chunkCount() == 0 && !kb.isIndexing() && (s.useBuiltinReference || !s.documents.isEmpty()))
			reindex();
		shell.pack();
		shell.setSize(Math.max(720, shell.getSize().x), Math.max(640, shell.getSize().y));
		PreferencesDialog.center(shell, parent);
		shell.open();
	}

	private void button(Composite c, String text, Runnable r) {
		Button b = new Button(c, SWT.PUSH);
		b.setText(text);
		b.addListener(SWT.Selection, _ -> r.run());
	}

	private static Button check(Composite c, String text, boolean v) {
		Button b = new Button(c, SWT.CHECK);
		b.setText(text);
		b.setSelection(v);
		b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
		return b;
	}

	private void saveNumbers() {
		try {
			s.docTopK = Math.max(1, Math.min(20, Integer.parseInt(topK.getText().trim())));
			s.docMaxChars = Math.max(500, Math.min(60000, Integer.parseInt(maxChars.getText().trim())));
			s.save();
		} catch(NumberFormatException ignored) {
		}
	}

	/** Fills the table from the index (and settings entries not indexed yet). */
	private void fill() {
		if(table.isDisposed())
			return;
		table.removeAll();
		java.util.Set<String> indexed = new java.util.HashSet<>();
		for(KnowledgeBase.DocInfo d : kb.documents()) {
			TableItem it = new TableItem(table, SWT.NONE);
			boolean child = !d.folder && d.entry != null && new File(d.entry).isDirectory();
			it.setText(new String[]{(child ? "    " : "") + d.name, d.type, "" + d.chunks, d.status == null ? "" : d.status});
			it.setData(d);
			if(d.entry != null)
				indexed.add(d.entry);
		}
		for(String e : s.documents) { // added but not indexed yet
			if(indexed.contains(e))
				continue;
			TableItem it = new TableItem(table, SWT.NONE);
			File f = new File(e);
			it.setText(new String[]{f.getName() + (f.isDirectory() ? "/" : ""), f.isDirectory() ? "folder" : "", "", "waiting for indexing ..."});
			it.setData(e);
		}
		shownVersion = kb.version();
		status.setText(kb.isIndexing() ? "Indexing ..." : kb.summary());
		status.getParent().layout();
		semanticStatus.setText("Status: " + kb.semanticStatus());
		semanticStatus.getParent().layout();
	}

	/** Refreshes the status while indexing / embedding runs. */
	private void poll() {
		if(shell == null || shell.isDisposed())
			return;
		String st = kb.semanticStatus();
		semanticStatus.setText("Status: " + st);
		if(kb.version() != shownVersion) { // a new keyword index was published
			fill();
			status.setText(kb.summary());
		} else if(kb.isIndexing())
			status.setText("Indexing ...");
		boolean busy = kb.isIndexing() || st.startsWith("embedding");
		shell.getDisplay().timerExec(busy ? 400 : 1500, this::poll);
	}

	private void applySemantic() {
		s.semanticSearch = semantic.getSelection();
		s.embeddingModel = embedModel.getText().trim().isEmpty() ? "text-embedding-3-small" : embedModel.getText().trim();
		s.embeddingBaseUrl = embedUrl.getText().trim();
		try {
			s.embeddingDimensions = Math.max(0, Integer.parseInt(embedDims.getText().trim()));
		} catch(NumberFormatException ignored) {
		}
		s.save();
		reindex();
	}

	private void fetchEmbeddingModels() {
		s.embeddingBaseUrl = embedUrl.getText().trim();
		semanticStatus.setText("Status: fetching embedding models ...");
		Display d = shell.getDisplay();
		new Thread(() -> {
			String msg;
			List<String> ids = null;
			try {
				ids = new OpenAIClient(s).listEmbeddingModels();
				msg = ids.isEmpty() ? "the server lists no embedding models (in LM Studio, load one first)" : ids.size() + " embedding model(s) found";
			} catch(Exception ex) {
				msg = "could not list models: " + ex.getMessage();
			}
			final List<String> f = ids;
			final String m = msg;
			d.asyncExec(() -> {
				if(shell.isDisposed())
					return;
				if(f != null && !f.isEmpty()) {
					String cur = embedModel.getText();
					embedModel.setItems(f.toArray(new String[0]));
					embedModel.setText(f.contains(cur) ? cur : f.get(0));
				}
				semanticStatus.setText("Status: " + m);
			});
		}, "LLM-Assistant-models").start();
	}

	private void addFiles() {
		FileDialog fd = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
		fd.setText("Add reference documents");
		// "All files" first: a type filter hides files with other (or upper-case) extensions,
		// which makes the dialog look as if it only showed folders
		fd.setFilterNames(new String[]{"All files", "Supported documents"});
		fd.setFilterExtensions(new String[]{"*", "*.txt;*.md;*.markdown;*.rst;*.html;*.htm;*.csv;*.tsv;*.json;*.xml;*.log;*.ijm;*.java;*.js;*.py;*.bsh;*.groovy;*.r;*.m;*.docx;*.odt;*.pdf;*.TXT;*.MD;*.HTML;*.PDF;*.DOCX"});
		fd.setFilterIndex(0);
		if(fd.open() == null) {
			status.setText("No file selected.");
			return;
		}
		List<String> added = new java.util.ArrayList<>();
		for(String n : fd.getFileNames())
			added.add(new File(fd.getFilterPath(), n).getAbsolutePath());
		addEntries(added);
	}

	/** Adds files/folders to the settings, reports what happened, and re-indexes. */
	private void addEntries(List<String> paths) {
		List<String> names = new java.util.ArrayList<>(), problems = new java.util.ArrayList<>();
		for(String p : paths) {
			File f = new File(p.trim().replaceAll("^\"|\"$", ""));
			if(!f.exists()) {
				problems.add(f.getName() + " (not found)");
				continue;
			}
			String abs = f.getAbsolutePath();
			if(s.documents.contains(abs)) {
				problems.add(f.getName() + " (already added)");
				continue;
			}
			if(f.isFile() && !KnowledgeBase.supported(f))
				problems.add(f.getName() + " (type " + KnowledgeBase.extension(f) + " not supported)");
			s.documents.add(abs); // unsupported files are listed too, with an explanation
			names.add(f.getName() + (f.isDirectory() ? "/" : ""));
		}
		if(!names.isEmpty()) {
			s.save();
			reindex();
		}
		fill(); // shows the new entries at once ("waiting for indexing ...")
		String msg = (names.isEmpty() ? "Nothing added" : "Added " + String.join(", ", names)) + (problems.isEmpty() ? "" : " - " + String.join(", ", problems));
		status.setText(msg.length() > 110 ? msg.substring(0, 110) + "..." : msg);
		status.setToolTipText(msg);
		status.getParent().layout();
	}

	private void addPath() {
		String p = pathText.getText().trim();
		if(p.isEmpty())
			return;
		if(p.startsWith("~"))
			p = System.getProperty("user.home") + p.substring(1);
		addEntries(List.of(p));
		pathText.setText("");
	}

	/** Files and folders dragged from the system file manager onto the list are added. */
	private void installDrop(org.eclipse.swt.widgets.Control target) {
		org.eclipse.swt.dnd.DropTarget dt = new org.eclipse.swt.dnd.DropTarget(target, org.eclipse.swt.dnd.DND.DROP_COPY | org.eclipse.swt.dnd.DND.DROP_DEFAULT | org.eclipse.swt.dnd.DND.DROP_LINK);
		dt.setTransfer(org.eclipse.swt.dnd.FileTransfer.getInstance());
		dt.addDropListener(new org.eclipse.swt.dnd.DropTargetAdapter() {

			@Override
			public void dragEnter(org.eclipse.swt.dnd.DropTargetEvent e) {
				if(e.detail == org.eclipse.swt.dnd.DND.DROP_DEFAULT)
					e.detail = org.eclipse.swt.dnd.DND.DROP_COPY;
			}

			@Override
			public void drop(org.eclipse.swt.dnd.DropTargetEvent e) {
				if(e.data instanceof String[])
					addEntries(java.util.Arrays.asList((String[])e.data));
			}
		});
	}

	private void addFolder() {
		DirectoryDialog dd = new DirectoryDialog(shell);
		dd.setText("Add a folder of reference documents (searched recursively)");
		String p = dd.open();
		if(p == null) {
			status.setText("No folder selected.");
			return;
		}
		addEntries(List.of(p));
	}

	private void remove() {
		for(TableItem it : table.getSelection()) {
			Object data = it.getData();
			if(data instanceof String) { // not indexed yet
				s.documents.remove(data);
				continue;
			}
			KnowledgeBase.DocInfo d = (KnowledgeBase.DocInfo)data;
			if("built-in".equals(d.path)) {
				s.useBuiltinReference = false;
				builtin.setSelection(false);
			} else if(d.entry != null) {
				// a file inside an added folder removes the whole folder entry
				s.documents.remove(d.entry);
			}
		}
		s.save();
		reindex();
	}

	/** Re-indexes in the background; the table updates as soon as the keyword index is ready (see poll()). */
	private void reindex() {
		Display d = shell.getDisplay();
		new Thread(() -> {
			kb.reindex(s);
			d.asyncExec(() -> {
				if(!shell.isDisposed())
					fill();
				if(onChange != null)
					onChange.run();
			});
		}, "LLM-Assistant-index").start();
	}

	private void testSearch() {
		String q = query.getText().trim();
		if(q.isEmpty())
			return;
		results.setText("Searching ...");
		Display d = shell.getDisplay();
		new Thread(() -> { // hybrid search may call the embedding server
			List<KnowledgeBase.Hit> hits = kb.search(q, s.docTopK);
			StringBuilder sb = new StringBuilder();
			if(hits.isEmpty())
				sb.append(kb.chunkCount() == 0 ? "No documents indexed." : "No matching excerpts.");
			else
				sb.append(hits.get(0).hybrid() ? "Hybrid search (keyword + semantic)\n\n" : "Keyword search (BM25)\n\n");
			for(KnowledgeBase.Hit h : hits) {
				String scores = h.hybrid() ? String.format("keyword %.1f, similarity %.2f", h.bm25, h.cosine) : String.format("score %.2f", h.score);
				sb.append("[").append(scores).append("] ").append(h.chunk.title).append('\n').append(h.chunk.text.length() > 600 ? h.chunk.text.substring(0, 600) + " ..." : h.chunk.text).append("\n\n");
			}
			d.asyncExec(() -> {
				if(!results.isDisposed())
					results.setText(sb.toString());
			});
		}, "LLM-Assistant-search").start();
	}
}
