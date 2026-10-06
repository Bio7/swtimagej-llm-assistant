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
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ij.IJ;

/**
 * Local reference documents that improve the model's answers: the built-in
 * ImageJ macro functions reference plus files and folders chosen by the user
 * (text, Markdown, HTML, code, .docx/.odt, and PDF if Apache PDFBox is
 * available). Documents are split into excerpts and searched with BM25, a
 * lexical ranking that needs no embedding model and works offline with any
 * server. Matching excerpts are attached to messages and searchable by the
 * model (search_documents).
 */
public final class KnowledgeBase {

	public static final String BUILTIN = "ImageJ macro functions reference (built-in)";
	public static final String[] TEXT_EXT = {".txt", ".md", ".markdown", ".rst", ".csv", ".tsv", ".json", ".xml", ".log"};
	public static final String[] CODE_EXT = {".ijm", ".java", ".js", ".py", ".bsh", ".groovy", ".r", ".m"};
	public static final String[] HTML_EXT = {".html", ".htm"};
	public static final String[] OFFICE_EXT = {".docx", ".odt"};
	public static final String[] PDF_EXT = {".pdf"};

	private static final double USER_BOOST = 1.3;
	private static final int CHUNK = 1400, MAX_FILE_BYTES = 60_000_000, MAX_CHUNKS = 40_000, MAX_FILES = 3000;
	private static KnowledgeBase instance;

	/** One searchable excerpt. */
	public static final class Chunk {

		public final String doc, title, text;
		final Map<String, Integer> tf = new HashMap<>();
		int length;

		Chunk(String doc, String title, String text) {
			this.doc = doc;
			this.title = title;
			this.text = text;
		}
	}

	/** Per-document status for the dialog. */
	public static final class DocInfo {

		public String name, path, type, status;
		/** The settings entry (file or folder) this row comes from, for exact removal. */
		public String entry;
		public int chunks;
		/** Folder summary row (its files follow as separate rows). */
		public boolean folder;
	}

	/** Search index (replaced as a whole when re-indexing; vectors are added when embedded). */
	private static final class Index {

		final List<Chunk> chunks = new ArrayList<>();
		final Map<String, List<int[]>> postings = new HashMap<>(); // term -> [chunk, tf]
		final List<DocInfo> docs = new ArrayList<>();
		double avgLength = 1;
		/** Function/identifier name in an excerpt title (e.g. "setautothreshold", "table.getcolumn") -> chunks. */
		final Map<String, List<Integer>> names = new HashMap<>();
		/** Unit-length embedding per chunk (null entries allowed); set when semantic search is ready. */
		volatile float[][] vectors;
		volatile String embeddingModel;
		volatile int embeddingDimensions;
	}

	private static final int MAX_SEMANTIC_CHUNKS = 20_000, BATCH = 64, RRF_K = 60, CANDIDATES = 50;
	private volatile Index index = new Index();
	private volatile boolean indexing;
	private volatile String semanticStatus = "off";
	private volatile LLMSettings settings;
	private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
	/** Recent query vectors (query text -> vector). */
	private final Map<String, float[]> queryCache = Collections.synchronizedMap(new java.util.LinkedHashMap<String, float[]>(64, 0.75f, true) {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
			return size() > 64;
		}
	});

	private KnowledgeBase() {
	}

	public static synchronized KnowledgeBase get() {
		if(instance == null)
			instance = new KnowledgeBase();
		return instance;
	}

	public boolean isIndexing() {
		return indexing;
	}

	public int chunkCount() {
		return index.chunks.size();
	}

	public List<DocInfo> documents() {
		return Collections.unmodifiableList(index.docs);
	}

	public String summary() {
		Index ix = index;
		if(indexing)
			return "indexing ...";
		if(ix.chunks.isEmpty())
			return "none";
		long docs = ix.docs.stream().filter(d -> !d.folder).count();
		return docs + " document(s), " + ix.chunks.size() + " excerpts" + (semanticReady() ? ", hybrid search" : "");
	}

	/** True when embeddings for the current index are available (hybrid search). */
	public boolean semanticReady() {
		return index.vectors != null;
	}

	/** Human-readable state of semantic search, e.g. "embedding 120/796 ..." or the last error. */
	public String semanticStatus() {
		return semanticStatus;
	}

	/* ================================================================ indexing */

	/**
	 * Rebuilds the index from the settings (call on a background thread). The
	 * keyword index is published first; embeddings (if enabled) are computed
	 * afterwards. A newer reindex() call aborts an older one.
	 */
	public void reindex(LLMSettings s) {
		int my = generation.incrementAndGet();
		settings = s;
		Index ix;
		synchronized(keywordLock) {
			if(my != generation.get())
				return; // superseded while waiting
			ix = buildKeywordIndex(s);
		}
		if(!s.semanticSearch) {
			semanticStatus = "off";
			return;
		}
		synchronized(embedLock) { // an older embedding run stops at its next batch (generation changed)
			if(my != generation.get())
				return;
			embedAll(ix, s, my);
		}
	}

	private final Object keywordLock = new Object(), embedLock = new Object();
	private volatile int version;

	/** Increases whenever a new keyword index is published (lets dialogs refresh). */
	public int version() {
		return version;
	}

	private Index buildKeywordIndex(LLMSettings s) {
		indexing = true;
		try {
			Index ix = new Index();
			if(s.useBuiltinReference)
				addDoc(ix, BUILTIN, "built-in", "html", () -> builtinChunks());
			int files = 0;
			for(String entry : s.documents) {
				File f = new File(entry);
				int first = ix.docs.size();
				if(f.isDirectory()) {
					/* summary row for the folder, so it is visible even if it contains no supported files */
					DocInfo folder = new DocInfo();
					folder.name = f.getName() + "/";
					folder.path = f.getAbsolutePath();
					folder.type = "folder";
					folder.folder = true;
					ix.docs.add(folder);
					List<File> list = new ArrayList<>();
					Map<String, Integer> skipped = new java.util.TreeMap<>();
					collect(f, list, 0, skipped);
					for(File x : list) {
						if(++files > MAX_FILES)
							break;
						addFile(ix, x, f.getName() + "/" + f.toPath().relativize(x.toPath()).toString().replace('\\', '/'));
					}
					int n = 0;
					for(int i = first + 1; i < ix.docs.size(); i++)
						n += ix.docs.get(i).chunks;
					folder.chunks = n;
					StringBuilder st = new StringBuilder(list.size() + " supported file(s)");
					if(!skipped.isEmpty()) {
						st.append("; not supported: ");
						skipped.forEach((ext, c) -> st.append(ext).append(" x").append(c).append(' '));
					}
					folder.status = list.isEmpty() ? "no supported files found" + (skipped.isEmpty() ? "" : " (" + st.substring(st.indexOf("not supported")) + ")") : st.toString().strip();
				} else {
					files++;
					addFile(ix, f, f.getName());
				}
				for(int i = first; i < ix.docs.size(); i++)
					ix.docs.get(i).entry = entry;
			}
			finish(ix);
			index = ix;
			version++;
			return ix;
		} finally {
			indexing = false;
		}
	}

	/* ================================================================ embeddings */

	private static String embedText(Chunk c) {
		String t = c.title + "\n" + c.text;
		return t.length() > 6000 ? t.substring(0, 6000) : t;
	}

	private static float[] normalize(float[] v) {
		double n = 0;
		for(float x : v)
			n += x * x;
		n = Math.sqrt(n);
		if(n > 0)
			for(int i = 0; i < v.length; i++)
				v[i] = (float)(v[i] / n);
		return v;
	}

	/** Computes (or loads from the cache) the embeddings of all chunks and enables hybrid search. */
	private void embedAll(Index ix, LLMSettings s, int my) {
		int n = ix.chunks.size();
		if(n == 0) {
			semanticStatus = "no excerpts";
			return;
		}
		if(n > MAX_SEMANTIC_CHUNKS) {
			semanticStatus = "off: too many excerpts (" + n + " > " + MAX_SEMANTIC_CHUNKS + ") - keyword search only";
			return;
		}
		String model = s.embeddingModel == null || s.embeddingModel.isBlank() ? "text-embedding-3-small" : s.embeddingModel.trim();
		EmbeddingCache cache = EmbeddingCache.open(model, s.embeddingBaseUrl == null || s.embeddingBaseUrl.isBlank() ? s.baseUrl : s.embeddingBaseUrl, s.embeddingDimensions);
		float[][] vecs = new float[n][];
		String[] keys = new String[n];
		List<Integer> missing = new ArrayList<>();
		for(int i = 0; i < n; i++) {
			keys[i] = EmbeddingCache.key(embedText(ix.chunks.get(i)));
			vecs[i] = cache.get(keys[i]);
			if(vecs[i] == null)
				missing.add(i);
		}
		OpenAIClient client = new OpenAIClient(s);
		try {
			for(int b = 0; b < missing.size(); b += BATCH) {
				if(my != generation.get()) {
					semanticStatus = "aborted";
					return;
				}
				semanticStatus = "embedding " + b + "/" + missing.size() + " new excerpts (" + model + ") ...";
				List<Integer> batch = missing.subList(b, Math.min(missing.size(), b + BATCH));
				List<String> texts = new ArrayList<>();
				for(int i : batch)
					texts.add(embedText(ix.chunks.get(i)));
				List<float[]> res = client.embed(model, texts, s.embeddingDimensions);
				for(int k = 0; k < batch.size(); k++) {
					int i = batch.get(k);
					vecs[i] = normalize(res.get(k));
					cache.put(keys[i], vecs[i]);
				}
			}
			cache.save(new HashSet<>(Arrays.asList(keys)));
			ix.embeddingModel = model;
			ix.embeddingDimensions = s.embeddingDimensions;
			ix.vectors = vecs;
			semanticStatus = "ready: " + n + " excerpts embedded with " + model + (missing.isEmpty() ? " (from cache)" : " (" + missing.size() + " new, rest from cache)");
		} catch(Throwable t) {
			cache.save(null); // keep what was computed
			semanticStatus = "unavailable: " + (t.getMessage() == null ? t.toString() : t.getMessage()) + " - using keyword search only";
		}
	}

	/** Embedding of a query (cached), or null if it cannot be computed. */
	private float[] queryVector(Index ix, String query) {
		String key = ix.embeddingModel + "|" + query;
		float[] v = queryCache.get(key);
		if(v != null)
			return v;
		LLMSettings s = settings;
		if(s == null)
			return null;
		try {
			v = normalize(new OpenAIClient(s).embed(ix.embeddingModel, List.of(query.length() > 6000 ? query.substring(0, 6000) : query), ix.embeddingDimensions).get(0));
			queryCache.put(key, v);
			return v;
		} catch(Throwable t) {
			semanticStatus = "query embedding failed: " + t.getMessage() + " - keyword search used";
			return null;
		}
	}

	private interface ChunkSource {

		List<Chunk> get() throws Exception;
	}

	private static void addDoc(Index ix, String name, String path, String type, ChunkSource src) {
		DocInfo d = new DocInfo();
		d.name = name;
		d.path = path;
		d.type = type;
		try {
			List<Chunk> cs = src.get();
			int room = MAX_CHUNKS - ix.chunks.size();
			if(cs.size() > room)
				cs = cs.subList(0, Math.max(0, room));
			ix.chunks.addAll(cs);
			d.chunks = cs.size();
			String note = NOTE.get();
			d.status = cs.isEmpty() ? "no text found" : "OK" + (note == null ? "" : " - " + note);
		} catch(Throwable t) {
			d.status = "ERROR: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
		} finally {
			NOTE.remove();
		}
		ix.docs.add(d);
	}

	private static void addFile(Index ix, File f, String name) {
		if(!f.isFile()) {
			DocInfo d = new DocInfo();
			d.name = name;
			d.path = f.getAbsolutePath();
			d.type = "?";
			d.status = "ERROR: file not found";
			ix.docs.add(d);
			return;
		}
		String type = typeOf(f);
		addDoc(ix, name, f.getAbsolutePath(), type, () -> {
			if(f.length() > MAX_FILE_BYTES)
				throw new Exception("file too large (" + f.length() / 1_000_000 + " MB)");
			switch(type) {
				case "pdf":
					return pdfChunks(f, name);
				case "html":
					return textChunks(name, htmlToText(read(f)));
				case "docx":
				case "odt":
					return textChunks(name, officeText(f));
				case "code":
					return codeChunks(name, read(f));
				case "text":
					return textChunks(name, read(f));
				default:
					throw new Exception("unsupported file type " + extension(f) + " - supported: text, Markdown, HTML, code, .docx, .odt, .pdf");
			}
		});
	}

	public static boolean supported(File f) {
		return !typeOf(f).equals("?");
	}

	/**
	 * Documents dialog default: does this file, or most of a folder's supported files, look
	 * like source code rather than prose/reference material? Used to default the "enable
	 * direct browse/search tools" checkbox on for a newly added folder.
	 */
	public static boolean looksLikeCode(File f) {
		if(f.isFile())
			return "code".equals(typeOf(f));
		if(!f.isDirectory())
			return false;
		int[] counts = {0, 0}; // code, other supported
		scanForCode(f, counts, 0);
		return counts[0] > 0 && counts[0] >= counts[1];
	}

	private static void scanForCode(File dir, int[] counts, int depth) {
		File[] fs = dir.listFiles();
		if(fs == null || depth > 6)
			return;
		for(File f : fs) {
			if(counts[0] + counts[1] >= 500)
				return;
			if(f.getName().startsWith("."))
				continue;
			if(f.isDirectory())
				scanForCode(f, counts, depth + 1);
			else if(f.isFile() && supported(f)) {
				if("code".equals(typeOf(f)))
					counts[0]++;
				else
					counts[1]++;
			}
		}
	}

	private static String typeOf(File f) {
		String n = f.getName().toLowerCase(Locale.ROOT);
		if(ends(n, PDF_EXT))
			return "pdf";
		if(ends(n, HTML_EXT))
			return "html";
		if(n.endsWith(".docx"))
			return "docx";
		if(n.endsWith(".odt"))
			return "odt";
		if(ends(n, CODE_EXT))
			return "code";
		if(ends(n, TEXT_EXT))
			return "text";
		return "?";
	}

	private static boolean ends(String n, String[] ext) {
		for(String e : ext)
			if(n.endsWith(e))
				return true;
		return false;
	}

	private static void collect(File dir, List<File> out, int depth, Map<String, Integer> skipped) {
		File[] fs = dir.listFiles();
		if(fs == null || depth > 6)
			return;
		Arrays.sort(fs);
		for(File f : fs) {
			if(out.size() >= MAX_FILES)
				return;
			if(f.getName().startsWith("."))
				continue;
			if(f.isDirectory())
				collect(f, out, depth + 1, skipped);
			else if(f.isFile() && supported(f))
				out.add(f);
			else if(f.isFile())
				skipped.merge(extension(f), 1, Integer::sum);
		}
	}

	static String extension(File f) {
		String n = f.getName();
		int i = n.lastIndexOf('.');
		return i <= 0 ? "(no extension)" : n.substring(i).toLowerCase(Locale.ROOT);
	}

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	/* ================================================================ chunking */

	/** The macro functions reference: one excerpt per documented function. */
	static List<Chunk> builtinChunks() throws Exception {
		String html = null;
		try {
			html = ij.util.Tools.openFromIJJarAsString("/functions.html");
		} catch(Throwable ignored) {
		}
		if(html == null || html.length() < 1000) {
			try(InputStream in = IJ.class.getResourceAsStream("/functions.html")) {
				if(in != null)
					html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		if(html == null)
			throw new Exception("functions.html not found in the SWTImageJ bundle");
		return functionChunks(html);
	}

	static List<Chunk> functionChunks(String html) {
		List<Chunk> out = new ArrayList<>();
		Matcher m = Pattern.compile("<a name=\"([^\"]+)\"></a>").matcher(html);
		List<int[]> starts = new ArrayList<>();
		List<String> names = new ArrayList<>();
		while(m.find()) {
			starts.add(new int[]{m.start(), m.end()});
			names.add(m.group(1));
		}
		for(int i = 0; i < starts.size(); i++) {
			String name = names.get(i);
			if(name.length() <= 1 || name.equals("Top"))
				continue; // letter index anchors
			int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : html.length();
			String body = htmlToText(html.substring(starts.get(i)[1], end)).strip();
			if(body.length() < 15)
				continue;
			Matcher b = Pattern.compile("<b>(.*?)</b>", Pattern.DOTALL).matcher(html.substring(starts.get(i)[1], end));
			String title = b.find() ? htmlToText(b.group(1)).strip() : name;
			out.add(new Chunk(BUILTIN, title, clip(body, 3000)));
		}
		return out;
	}

	/** Splits text into sections by Markdown/HTML-converted headings, then into ~CHUNK-sized parts. */
	static List<Chunk> textChunks(String doc, String text) {
		List<Chunk> out = new ArrayList<>();
		String[] stack = new String[7]; // heading per level, for titles like "doc > Protocol > Segmentation"
		String heading = "";
		StringBuilder section = new StringBuilder();
		for(String line : text.replace("\r", "").split("\n")) {
			if(line.matches("^#{1,6}\\s+.*")) {
				flush(out, doc, heading, section.toString());
				section.setLength(0);
				int level = line.indexOf(' ');
				level = Math.max(1, Math.min(6, level));
				stack[level] = line.replaceFirst("^#+\\s+", "").strip();
				for(int l = level + 1; l < stack.length; l++)
					stack[l] = null;
				StringBuilder h = new StringBuilder();
				for(int l = 1; l < stack.length; l++)
					if(stack[l] != null && !stack[l].isEmpty())
						h.append(h.length() > 0 ? " \u203a " : "").append(stack[l]);
				heading = h.toString();
				continue; // the heading is part of the title, not of the excerpt text
			}
			section.append(line).append('\n');
		}
		flush(out, doc, heading, section.toString());
		return out;
	}

	private static void flush(List<Chunk> out, String doc, String heading, String section) {
		String s = section.strip();
		if(s.isEmpty())
			return;
		String title = heading.isEmpty() ? doc : doc + " \u203a " + heading;
		if(s.length() <= CHUNK) {
			out.add(new Chunk(doc, title, s));
			return;
		}
		// paragraphs, merged up to CHUNK, with the previous paragraph repeated for context
		String[] paras = s.split("\\n\\s*\\n");
		StringBuilder cur = new StringBuilder();
		String last = "";
		int part = 1;
		for(String p : paras) {
			p = p.strip();
			if(p.isEmpty())
				continue;
			for(String piece : splitLong(p)) {
				if(cur.length() + piece.length() > CHUNK && cur.length() > 0) {
					out.add(new Chunk(doc, title + " (part " + part++ + ")", cur.toString().strip()));
					cur.setLength(0);
					if(last.length() < CHUNK / 3)
						cur.append(last).append("\n\n");
				}
				cur.append(piece).append("\n\n");
				last = piece;
			}
		}
		if(cur.length() > 0)
			out.add(new Chunk(doc, title + (part > 1 ? " (part " + part + ")" : ""), cur.toString().strip()));
	}

	private static List<String> splitLong(String p) {
		List<String> l = new ArrayList<>();
		while(p.length() > CHUNK) {
			int cut = p.lastIndexOf(". ", CHUNK);
			if(cut < CHUNK / 2)
				cut = p.lastIndexOf(' ', CHUNK);
			if(cut < CHUNK / 2)
				cut = CHUNK;
			l.add(p.substring(0, cut + 1).strip());
			p = p.substring(cut + 1);
		}
		if(!p.isBlank())
			l.add(p.strip());
		return l;
	}

	/** Code: windows of 60 lines overlapping by 10. */
	static List<Chunk> codeChunks(String doc, String code) {
		List<Chunk> out = new ArrayList<>();
		String[] lines = code.replace("\r", "").split("\n", -1);
		for(int start = 0; start < lines.length; start += 50) {
			int end = Math.min(lines.length, start + 60);
			String t = String.join("\n", Arrays.copyOfRange(lines, start, end)).strip();
			if(!t.isEmpty())
				out.add(new Chunk(doc, doc + " lines " + (start + 1) + "-" + end, t));
			if(end == lines.length)
				break;
		}
		return out;
	}

	private static final Map<String, String> ENTITIES = Map.of("&nbsp;", " ", "&lt;", "<", "&gt;", ">", "&amp;", "&", "&quot;", "\"", "&#39;", "'", "&apos;", "'");

	/** HTML to plain text keeping headings (as Markdown), paragraphs, list items and line breaks. */
	static String htmlToText(String html) {
		String s = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
		s = s.replaceAll("(?is)<h([1-6])[^>]*>(.*?)</h\\1>", "\n\n# $2\n\n");
		s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</?(p|div|tr|table|ul|ol|pre|blockquote)[^>]*>", "\n\n").replaceAll("(?i)<li[^>]*>", "\n- ").replaceAll("(?i)</t[dh]>", "\t");
		s = s.replaceAll("(?s)<[^>]+>", "");
		for(Map.Entry<String, String> e : ENTITIES.entrySet())
			s = s.replace(e.getKey(), e.getValue());
		Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
		StringBuilder sb = new StringBuilder();
		while(m.find())
			m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char)Integer.parseInt(m.group(1)))));
		m.appendTail(sb);
		return sb.toString().replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
	}

	/** Text of .docx (word/document.xml) or .odt (content.xml), one paragraph per line, headings as Markdown. */
	static String officeText(File f) throws Exception {
		boolean docx = f.getName().toLowerCase(Locale.ROOT).endsWith(".docx");
		try(ZipFile z = new ZipFile(f)) {
			ZipEntry e = z.getEntry(docx ? "word/document.xml" : "content.xml");
			if(e == null)
				throw new Exception("not a valid " + (docx ? "Word" : "OpenDocument") + " file");
			String xml;
			try(InputStream in = z.getInputStream(e)) {
				xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			if(docx) {
				// heading styles sit in <w:pPr> before the text runs: leave a marker that survives tag stripping
				xml = xml.replaceAll("<w:pStyle w:val=\"[^\"]{0,4}(?:Heading|heading|berschrift|Titel|Title)\\d*\"\\s*/>", "\u0001");
				xml = xml.replaceAll("</w:p>", "\n\n").replaceAll("<w:tab/>", "\t").replaceAll("<w:br/>", "\n");
			} else {
				xml = xml.replaceAll("<text:h[^>]*>", "\n# ").replaceAll("</text:(p|h)>", "\n\n").replaceAll("<text:tab/>", "\t").replaceAll("<text:line-break/>", "\n");
			}
			return htmlToText(xml).replace("\u0001", "\n# ");
		}
	}

	/** PDF text per page via Apache PDFBox (2.x or 3.x jar in the plugins folder), loaded by reflection. */
	static List<Chunk> pdfChunks(File f, String doc) throws Exception {
		ClassLoader cl = IJ.getClassLoader() != null ? IJ.getClassLoader() : KnowledgeBase.class.getClassLoader();
		Class<?> stripperClass;
		try {
			stripperClass = Class.forName("org.apache.pdfbox.text.PDFTextStripper", true, cl);
		} catch(ClassNotFoundException e) {
			throw new Exception("PDF needs Apache PDFBox: put pdfbox-app-*.jar into the SWTImageJ plugins/jars folder and restart (or convert the PDF to text)");
		}
		quietPdfBoxLogging(cl);
		Class<?> docClass = Class.forName("org.apache.pdfbox.pdmodel.PDDocument", true, cl);
		Object pd;
		try {
			Class<?> loader = Class.forName("org.apache.pdfbox.Loader", true, cl); // PDFBox 3
			pd = loader.getMethod("loadPDF", File.class).invoke(null, f);
		} catch(ClassNotFoundException e) {
			pd = docClass.getMethod("load", File.class).invoke(null, f); // PDFBox 2
		} catch(java.lang.reflect.InvocationTargetException e) {
			throw new Exception("PDFBox cannot open the file: " + rootMessage(e));
		}
		try {
			int pages = (Integer)docClass.getMethod("getNumberOfPages").invoke(pd);
			Method setStart = stripperClass.getMethod("setStartPage", int.class), setEnd = stripperClass.getMethod("setEndPage", int.class), getText = stripperClass.getMethod("getText", docClass);
			Object stripper = stripperClass.getConstructor().newInstance();
			List<Chunk> out = new ArrayList<>();
			int failed = 0, garbled = 0, empty = 0;
			String firstError = null;
			for(int p = 1; p <= pages; p++) {
				String text;
				try { // pages are read one by one: a problem on one page must not lose the whole document
					setStart.invoke(stripper, p);
					setEnd.invoke(stripper, p);
					text = ((String)getText.invoke(stripper, pd)).strip();
				} catch(Throwable t) {
					failed++;
					if(firstError == null)
						firstError = "p." + p + ": " + rootMessage(t);
					stripper = stripperClass.getConstructor().newInstance(); // fresh state after an error
					continue;
				}
				if(text.isEmpty()) {
					empty++;
					continue;
				}
				if(readableRatio(text) < 0.75) { // font without Unicode mapping: text would be gibberish
					garbled++;
					continue;
				}
				for(Chunk c : textChunks(doc, text))
					out.add(new Chunk(doc, doc + " p." + p + (c.title.contains("(part") ? c.title.substring(c.title.indexOf(" (part")) : ""), c.text));
			}
			if(out.isEmpty()) {
				if(garbled > 0)
					throw new Exception("text cannot be extracted: the fonts have no Unicode mapping (" + garbled + " of " + pages + " pages garbled) - the PDF needs OCR or a text export");
				if(failed > 0)
					throw new Exception("PDFBox could not read any page (" + firstError + ")");
				throw new Exception("no text layer (scanned PDF?) - the PDF needs OCR");
			}
			List<String> notes = new ArrayList<>();
			if(garbled > 0)
				notes.add(garbled + " page(s) skipped: garbled text (font without Unicode mapping)");
			if(failed > 0)
				notes.add(failed + " page(s) unreadable (" + firstError + ")");
			if(empty > 0 && empty < pages)
				notes.add(empty + " page(s) without text");
			if(!notes.isEmpty())
				NOTE.set(pages + " pages; " + String.join("; ", notes));
			return out;
		} finally {
			try {
				docClass.getMethod("close").invoke(pd);
			} catch(Throwable ignored) {
			}
		}
	}

	/** Remark about a partially read document, picked up by addDoc for the status column. */
	private static final ThreadLocal<String> NOTE = new ThreadLocal<>();

	private static String rootMessage(Throwable t) {
		Throwable r = t;
		while(r.getCause() != null && r.getCause() != r)
			r = r.getCause();
		return r.getClass().getSimpleName() + (r.getMessage() != null ? ": " + r.getMessage() : "");
	}

	/**
	 * Share of "normal" characters (letters, digits, whitespace, common
	 * punctuation). Text extracted from fonts without a Unicode mapping consists
	 * of control, private-use or replacement characters and scores low.
	 */
	static double readableRatio(String text) {
		int good = 0, n = 0;
		for(int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			n++;
			if(Character.isLetterOrDigit(c) || Character.isWhitespace(c) || ".,;:!?()[]{}-+*/=<>%&'\"°µ–—’“”±×".indexOf(c) >= 0) {
				if(c < 0xE000 || c > 0xF8FF) // private use area = unmapped glyphs
					good++;
			}
		}
		return n == 0 ? 1 : (double)good / n;
	}

	private static volatile boolean pdfLoggingQuiet;

	/**
	 * Lowers PDFBox's own log output to errors. PDFBox reports recoverable font
	 * problems (e.g. "No glyph for U+0020") with full stack traces, which floods
	 * consoles running at DEBUG/WARN level. Only the org.apache.pdfbox loggers
	 * are changed (logback via SLF4J, java.util.logging, commons-logging SimpleLog).
	 */
	private static void quietPdfBoxLogging(ClassLoader cl) {
		if(pdfLoggingQuiet)
			return;
		pdfLoggingQuiet = true;
		java.util.logging.Logger.getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.SEVERE);
		System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.pdfbox", "error");
		for(ClassLoader l : new ClassLoader[]{cl, KnowledgeBase.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
			if(l == null)
				continue;
			try {
				Object logger = Class.forName("org.slf4j.LoggerFactory", true, l).getMethod("getLogger", String.class).invoke(null, "org.apache.pdfbox");
				if(logger != null && logger.getClass().getName().equals("ch.qos.logback.classic.Logger")) {
					Class<?> level = Class.forName("ch.qos.logback.classic.Level", true, logger.getClass().getClassLoader());
					logger.getClass().getMethod("setLevel", level).invoke(logger, level.getField("ERROR").get(null));
				}
			} catch(Throwable ignored) {
				// no SLF4J/logback visible from this loader
			}
			try { // log4j2 core, if that is the backend
				Class<?> conf = Class.forName("org.apache.logging.log4j.core.config.Configurator", true, l);
				Class<?> level = Class.forName("org.apache.logging.log4j.Level", true, l);
				conf.getMethod("setLevel", String.class, level).invoke(null, "org.apache.pdfbox", level.getField("ERROR").get(null));
			} catch(Throwable ignored) {
			}
		}
	}

	/* ================================================================ BM25 */

	private static final Set<String> STOP = new HashSet<>(Arrays.asList("a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "for", "from", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my", "of", "on", "or", "so", "that", "the", "this", "to", "was", "what", "when", "which", "with", "you", "your", "use", "using", "der", "die", "das", "und", "ist", "ein", "eine", "mit", "wie", "zu", "von", "den", "im"));
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

	/** Very light English stemming so that count/counting/counts and particle/particles match. */
	static String stem(String w) {
		int n = w.length();
		if(n <= 4 || !Character.isLetter(w.charAt(n - 1)))
			return w;
		if(w.endsWith("ies") && n > 5)
			return w.substring(0, n - 3) + "y";
		if(w.endsWith("ing") && n > 6)
			return w.substring(0, n - 3);
		if(w.endsWith("ed") && n > 5 && !w.endsWith("eed"))
			return w.substring(0, n - 2);
		if(w.endsWith("es") && (w.endsWith("sses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")))
			return w.substring(0, n - 2);
		if(w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is"))
			return w.substring(0, n - 1);
		return w;
	}

	/** Lower-case words; identifiers are also split at camelCase/underscores (setAutoThreshold -> set, auto, threshold). */
	static List<String> tokens(String text) {
		List<String> out = new ArrayList<>();
		Matcher m = WORD.matcher(text);
		while(m.find()) {
			String w = m.group();
			String lw = w.toLowerCase(Locale.ROOT);
			if(lw.length() > 1 && !STOP.contains(lw))
				out.add(stem(lw));
			if(w.length() > 3 && (w.indexOf('_') > 0 || !w.equals(lw) && !w.equals(w.toUpperCase(Locale.ROOT)))) {
				for(String part : w.split("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
					String lp = part.toLowerCase(Locale.ROOT);
					if(lp.length() > 1 && !lp.equals(lw) && !STOP.contains(lp))
						out.add(stem(lp));
				}
			}
		}
		return out;
	}

	private static void finish(Index ix) {
		long total = 0;
		for(int i = 0; i < ix.chunks.size(); i++) {
			Chunk c = ix.chunks.get(i);
			List<String> toks = tokens(c.text);
			List<String> titleToks = tokens(c.title);
			toks.addAll(titleToks); // titles count twice
			toks.addAll(titleToks);
			for(String t : toks)
				c.tf.merge(t, 1, Integer::sum);
			c.length = toks.size();
			total += c.length;
			for(Map.Entry<String, Integer> e : c.tf.entrySet())
				ix.postings.computeIfAbsent(e.getKey(), _ -> new ArrayList<>()).add(new int[]{i, e.getValue()});
		}
		ix.avgLength = ix.chunks.isEmpty() ? 1 : (double)total / ix.chunks.size();
		for(int i = 0; i < ix.chunks.size(); i++) {
			String name = titleName(ix.chunks.get(i).title);
			if(name != null)
				ix.names.computeIfAbsent(name, _ -> new ArrayList<>()).add(i);
		}
	}

	private static final Pattern TITLE_NAME = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.]*)\\s*(\\(|$)");
	private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*[A-Za-z0-9_]");

	/** Identifier an excerpt documents, if its title looks like one (setAutoThreshold(method) -> setautothreshold). */
	static String titleName(String title) {
		Matcher m = TITLE_NAME.matcher(title);
		if(!m.find())
			return null;
		String n = m.group(1);
		boolean codeLike = m.group(2).equals("(") || n.indexOf('.') > 0 || !n.equals(n.toLowerCase(Locale.ROOT));
		return codeLike && n.length() >= 3 ? n.toLowerCase(Locale.ROOT) : null;
	}

	/** Chunks whose documented name appears literally in the query (e.g. "setAutoThreshold"). */
	private static List<Integer> exactNameMatches(Index ix, String query) {
		List<Integer> out = new ArrayList<>();
		Matcher m = QUERY_NAME.matcher(query);
		Set<String> seen = new HashSet<>();
		while(m.find()) {
			String w = m.group().toLowerCase(Locale.ROOT);
			if(seen.add(w) && ix.names.containsKey(w))
				out.addAll(ix.names.get(w));
		}
		return out;
	}

	/** A search hit. */
	public static final class Hit {

		public final Chunk chunk;
		public final double score;
		/** BM25 score (0 = no keyword match) and cosine similarity (NaN = keyword search only). */
		public double bm25, cosine = Double.NaN;

		Hit(Chunk c, double s) {
			chunk = c;
			score = s;
		}

		public boolean hybrid() {
			return !Double.isNaN(cosine);
		}
	}

	/**
	 * Searches the documents: BM25 keyword search, combined with embedding
	 * similarity by reciprocal rank fusion when semantic search is ready. May call
	 * the embedding server - do not call on the UI thread.
	 */
	public List<Hit> search(String query, int max) {
		Index ix = index;
		if(ix.chunks.isEmpty() || query == null || query.isBlank())
			return Collections.emptyList();
		List<Hit> lexical = bm25(ix, query, ix.vectors != null ? CANDIDATES : max);
		float[][] vecs = ix.vectors;
		float[] q = vecs == null ? null : queryVector(ix, query);
		if(q == null)
			return lexical.subList(0, Math.min(max, lexical.size()));
		/* vector candidates */
		List<Hit> semantic = new ArrayList<>();
		for(int i = 0; i < vecs.length; i++) {
			float[] v = vecs[i];
			if(v == null || v.length != q.length)
				continue;
			double dot = 0;
			for(int k = 0; k < v.length; k++)
				dot += v[k] * q[k];
			Hit h = new Hit(ix.chunks.get(i), dot);
			h.cosine = dot;
			semantic.add(h);
		}
		semantic.sort((a, b) -> Double.compare(b.score, a.score));
		if(semantic.size() > CANDIDATES)
			semantic = semantic.subList(0, CANDIDATES);
		/* reciprocal rank fusion of keyword, semantic and exact-name rankings */
		Map<Chunk, double[]> fused = new java.util.LinkedHashMap<>(); // chunk -> {rrf, bm25, cosine}
		// an exact function name in the query must win even if the embedding model is weak on identifiers
		for(int i : exactNameMatches(ix, query)) {
			Chunk c = ix.chunks.get(i);
			fused.computeIfAbsent(c, _ -> new double[]{0, 0, Double.NaN})[0] += 2.0 / (RRF_K + 1);
		}
		for(int r = 0; r < lexical.size(); r++) {
			Hit h = lexical.get(r);
			fused.computeIfAbsent(h.chunk, _ -> new double[]{0, 0, Double.NaN})[0] += 1.0 / (RRF_K + r + 1);
			fused.get(h.chunk)[1] = h.bm25;
		}
		for(int r = 0; r < semantic.size(); r++) {
			Hit h = semantic.get(r);
			fused.computeIfAbsent(h.chunk, _ -> new double[]{0, 0, Double.NaN})[0] += 1.0 / (RRF_K + r + 1);
			fused.get(h.chunk)[2] = h.cosine;
		}
		List<Hit> out = new ArrayList<>();
		for(Map.Entry<Chunk, double[]> e : fused.entrySet()) {
			double[] v = e.getValue();
			Chunk c = e.getKey();
			Hit h = new Hit(c, v[0] * (BUILTIN.equals(c.doc) ? 1.0 : 1.15));
			h.bm25 = v[1] > 0 ? v[1] : (titleName(c.title) != null && query.toLowerCase(Locale.ROOT).contains(titleName(c.title)) ? Double.MAX_VALUE / 4 : 0);
			h.cosine = Double.isNaN(v[2]) ? cosineOf(vecs, ix, c, q) : v[2];
			out.add(h);
		}
		out.sort((a, b) -> Double.compare(b.score, a.score));
		return out.subList(0, Math.min(max, out.size()));
	}

	private static double cosineOf(float[][] vecs, Index ix, Chunk c, float[] q) {
		int i = ix.chunks.indexOf(c);
		if(i < 0 || vecs[i] == null || vecs[i].length != q.length)
			return 0;
		double dot = 0;
		for(int k = 0; k < q.length; k++)
			dot += vecs[i][k] * q[k];
		return dot;
	}

	/** BM25 keyword search (k1 = 1.2, b = 0.75), user documents boosted. */
	private static List<Hit> bm25(Index ix, String query, int max) {
		Map<Integer, Double> scores = new HashMap<>();
		int n = ix.chunks.size();
		for(String t : new java.util.LinkedHashSet<>(tokens(query))) {
			List<int[]> post = ix.postings.get(t);
			if(post == null)
				continue;
			double idf = Math.log(1 + (n - post.size() + 0.5) / (post.size() + 0.5));
			for(int[] p : post) {
				Chunk c = ix.chunks.get(p[0]);
				double tf = p[1];
				double s = idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * c.length / ix.avgLength));
				scores.merge(p[0], s, Double::sum);
			}
		}
		List<Hit> hits = new ArrayList<>();
		for(Map.Entry<Integer, Double> e : scores.entrySet()) {
			Chunk c = ix.chunks.get(e.getKey());
			// documents the user added on purpose are more specific than the general reference
			Hit h = new Hit(c, e.getValue() * (BUILTIN.equals(c.doc) ? 1.0 : USER_BOOST));
			h.bm25 = h.score;
			hits.add(h);
		}
		hits.sort((a, b) -> Double.compare(b.score, a.score));
		return hits.subList(0, Math.min(max, hits.size()));
	}

	/**
	 * Formats the best excerpts for the model, within maxChars. Weak matches
	 * (score below 25% of the best one) are left out.
	 */
	public String excerpts(String query, int max, int maxChars, List<String> usedDocs) {
		List<Hit> hits = search(query, max);
		if(hits.isEmpty())
			return "";
		double best = hits.get(0).score, bestBm25 = 0, bestCos = -1;
		for(Hit h : hits) {
			bestBm25 = Math.max(bestBm25, h.bm25);
			if(h.hybrid())
				bestCos = Math.max(bestCos, h.cosine);
		}
		StringBuilder sb = new StringBuilder();
		int k = 0;
		for(Hit h : hits) {
			boolean relevant = h.hybrid() ? (h.bm25 > 0 && h.bm25 >= bestBm25 * 0.25) || h.cosine >= bestCos - 0.12 : h.score >= best * 0.25;
			if(!relevant)
				continue;
			String block = "--- [" + (++k) + "] " + h.chunk.title + (h.chunk.title.startsWith(h.chunk.doc) ? "" : " (" + h.chunk.doc + ")") + "\n" + clip(h.chunk.text, 1800) + "\n";
			if(sb.length() + block.length() > maxChars && sb.length() > 0)
				break;
			sb.append(block);
			if(usedDocs != null && !usedDocs.contains(h.chunk.doc))
				usedDocs.add(h.chunk.doc);
		}
		return sb.toString();
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + " ...";
	}
}
