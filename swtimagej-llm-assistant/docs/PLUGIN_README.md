# LLM Assistant for SWTImageJ

Connects SWTImageJ to a large language model (OpenAI or any OpenAI-compatible
server) with a chat window that can read and write the macro/script editor,
inspect the open images and run macros.

## Script Explorer (default workspace)

In SWTImageJ versions that include the **Script Explorer**
(*Plugins > Utilities > Script Explorer...*), the assistant uses it by default:

* The Script Explorer is opened only when needed (e.g. for a new tab or
  `open_script`) or with the *Script Explorer* button – not at startup.
* The **selected Script Explorer tab** is the editor the assistant reads and writes.
* New code (from the model or the *New tab* button) opens in a **new tab**.
* The model can browse, read and open the scripts in the Script Explorer's
  folders (ImageJ `plugins` and `macros`) to reuse existing code. Access is
  limited to files inside these two folders.
* The *Editor* list in the chat shows `Explorer: …` tabs and `Window: …`
  standalone editors; choose one to pin it, or keep *Auto*.

This can be switched off in the preferences. In older SWTImageJ
versions without the Script Explorer, standalone editor windows are used.

## Images: sending and opening

**Sending images to the model (vision).** Needs a vision-capable model
(e.g. GPT-4o / GPT-4.1 family, or a vision model in LM Studio such as Qwen2-VL, Gemma 3, LLaVA).

* Choose **Attach ▾ > Attach snapshot** to send the active image with your message, or click
  *Quick actions ▾ > Analyze image* for a visual analysis in one click.
* The model can also look by itself with `view_image` (any open image, optionally
  cropped to the selection) – e.g. to check a threshold or mask after running a macro.
* Snapshots are rendered as displayed (LUT, brightness/contrast, overlay, current
  slice; composites merged), scaled to at most *Max. snapshot size* (default 1024 px)
  and sent as PNG. The original pixel data is never modified or uploaded.
* Only the two most recent images stay in the conversation that is re-sent with
  each request; older ones are replaced by a note to save tokens.

**Opening images from the model.** With `open_image` the model opens a local file,
an http(s) URL or an ImageJ sample image (`list_sample_images`, e.g. "Blobs"),
and `select_image` switches the active image.

## Java

Java is a full language of the assistant (choose *Java* under *Write*, or ask for a plugin):

* `run_code` with language `java` compiles **in memory** with `javac` and runs the code
  in SWTImageJ. Compiler errors (with line numbers) and exceptions (with stack
  lines) go back to the model, which fixes the code and retries.
* Two forms are accepted:
  * **Statements only** – wrapped into an `ij.plugin.PlugIn` with the common imports
    (`ij.*`, `ij.gui.*`, `ij.process.*`, `ij.measure.*`, `ij.plugin.*`,
    `ij.plugin.filter.*`, `ij.plugin.frame.*`, `ij.io.*`, `ij.text.*`,
    `java.util.*`, `java.io.*`). Own `import` lines at the top are allowed.
  * **A complete class** implementing `PlugIn`, `PlugInFilter` (runs on the
    active image) or with a `static main` method.
* `save_java_plugin` installs a finished class: `Name.java` and the `.class` files
  go into `plugins/LLM_Plugins/` (or a chosen subfolder / the package folder) and
  the menus are refreshed. Names with an underscore (e.g. `Count_Cells`) appear
  under *Plugins > LLM Plugins*. Asks for approval when *Ask before running* is on.
* New Java tabs are named after the class (`Count_Cells.java`) so they can be
  saved and compiled with the Script Explorer's own Run button, too.
* *Quick actions ▾ > Convert to Java plugin* converts the editor code (e.g. a macro) into a
  Java plugin, opens it in a new tab and test-compiles it.
* The compile classpath is built from the real locations of the ImageJ, SWT and
  plugin classes (plus jars in the plugins folder, same rule as ImageJ's
  compiler), so it also works when SWTImageJ runs as an Eclipse/OSGi application.
* **Requires a JDK**: if SWTImageJ runs on a JRE without `javac`, the assistant
  reports this; macros and scripts keep working.

## Reference documents

Documents improve the answers with knowledge the model does not have, or
gets wrong: exact macro function signatures, your lab protocols, microscope
settings, project conventions, example macros.

* Click **⚙ > Documents...** in the chat window to add files or whole folders
  (searched recursively), remove them, re-index, and try a **test search**
  that shows which excerpts a question would retrieve.
* Three ways to add: *Add files...* / *Add folder...* (the file dialog shows
  all files by default), **drag & drop** files or folders from the file manager
  onto the list, or paste a path into the **Path** field.
* Every added entry appears in the list at once. Folders get a summary row
  (supported files found, and which file types were skipped); unsupported files
  are listed with the reason, e.g. `.doc` (old Word format – save as .docx).
* Built in: the **ImageJ macro functions reference** (`functions.html` from the
  SWTImageJ bundle), indexed with one excerpt per macro function – on by default.
* Supported: `.txt .md .rst .html .csv .tsv .json .xml .log`, code
  (`.ijm .java .js .py .bsh .groovy .r .m`), Word `.docx`, OpenDocument `.odt`,
  and `.pdf` if Apache PDFBox (`pdfbox-app-*.jar`, 2.x or 3.x) is in the
  SWTImageJ `plugins/jars` folder. Scanned PDFs without a text layer can't be read.
  PDFs are read page by page: pages PDFBox cannot read, and pages whose text
  comes out garbled (embedded fonts without Unicode mapping), are skipped and
  reported in the status column (e.g. *OK – 14 pages; 2 page(s) skipped*).
  PDFBox's own log output is reduced to errors, as it reports recoverable font
  problems (e.g. `No glyph for U+0020 in font …`) with full stack traces.
* With **Attach ▾ > Use documents** switched on, the best-matching excerpts (default 4, at most
  6000 characters) are attached to your message; the transcript shows which
  documents were used. The model can also search on its own (`search_documents`),
  e.g. to check a function signature before writing code, and is asked to name
  the documents it relied on.
* Search is local and offline: documents are split into excerpts (Markdown/HTML
  headings become excerpt titles like *protocol.md › Segmentation*, code in
  60-line windows, PDFs per page) and ranked with **BM25** – including
  camelCase splitting (`setAutoThreshold` → set, auto, threshold) and light
  stemming. Your own documents rank slightly above the built-in reference.
  No embedding model is needed, so it works the same with OpenAI and local servers.
* The index is built in the background when the chat opens; only the excerpts
  that match are sent to the server, never whole documents.

### Direct source browsing/search – optional

Excerpt search is built for prose-style Q&A; it's less precise for "find every
caller of this method" style questions, where the model needs to look at the
real file structure rather than a ranked snippet. Tick the checkbox next to a
document folder or file to also let the model browse, grep and read it directly:

* Each row in the Documents list has a checkbox (new column on the left of the
  table). Checking it enables four extra tools scoped to that entry: it has no
  effect on `search_documents`/excerpt indexing, which keeps working the same
  whether the checkbox is on or off.
* A newly added folder or file is checked **automatically** when it looks like
  source code (most of its supported files are `.ijm .java .js .py .bsh .groovy
  .r .m`); prose/reference material (Markdown, PDF, …) defaults to unchecked.
  Toggle it either way at any time – a file row inside an indexed folder
  toggles the whole folder, same as removing one removes the whole folder.
* `list_source_files` – lists files under the enabled entries, optionally
  filtered by a name substring and/or extension.
* `search_source` – greps the enabled entries' file contents with a regular
  expression (plain text also works) and returns matching `file:line: text`.
* `read_source_file` – returns a file's content with line numbers, optionally
  restricted to a line range, so the model can pull full context around a
  `search_source`/`find_symbol` hit.
* `find_symbol` – a regex-based (not a real parser) guess at where a class,
  method or function name is declared; verify with `read_source_file`.
* These tools only ever read files under the checked entries (path-traversal
  is blocked the same way as `read_script`'s Script Explorer sandbox) and are
  not gated behind **Confirm before running code** – they're read-only, so the
  checkbox itself is the consent, not a per-call approval dialog.

### Semantic (hybrid) search – optional

Keyword search is strong on exact terms (function names, settings, numbers)
but misses paraphrases, synonyms and questions in another language than the
documents. Tick **Hybrid search** under *Semantic search* in the Documents
dialog to combine it with embeddings – this is a RAG setup with hybrid retrieval:

* Embeddings come from the server's `/v1/embeddings` endpoint: OpenAI
  (`text-embedding-3-small`, shortened to 512 dimensions by default) or a local
  embedding model in LM Studio (load it, then *Fetch*). An optional separate
  *Embedding server* lets you e.g. chat with OpenAI and embed locally.
* Three rankings are merged by **reciprocal rank fusion**: BM25 keywords,
  embedding similarity, and exact function-name matches (so `setAutoThreshold`
  always finds its own entry, even if the embedding model is weak on identifiers).
* Embeddings are **cached on disk** per model/server (`LLM_Assistant_embeddings/`
  next to the settings file), keyed by the text of each excerpt – unchanged
  excerpts are never embedded again. The built-in reference (~800 excerpts) costs
  a fraction of a cent once with OpenAI.
* The keyword index is usable immediately; embeddings fill in in the background
  (progress in the dialog). If the embedding server is unreachable, search
  falls back to keywords and the status says why.
* The test search shows both scores (*keyword* and *similarity*) per excerpt.

## Error markers and quick fixes

With a recent SWTImageJ (editor marker API: `Editor.addMarker`, `Editor.QuickFix`,
`applyQuickFix`), the assistant shows problems directly in the editor – a
squiggly underline plus a dot in the ruler, the message on hover – and can
attach **quick fixes**. **Double-click** the marked text to apply a fix.

* **Find errors:** *Quick actions ▾ > Find errors & add quick fixes* – the model
  compiles Java (`check_code`), reviews the code (including ImageJ command names,
  checked with `search_commands`) and places markers with corrected replacement
  text via `mark_issues`. It does not change the code itself.
* **Automatic markers:** when the model runs the editor (`run_editor`), Java
  compiler errors (exact character ranges), Java runtime exceptions (failing
  line) and macro errors (line reported by the interpreter) are marked in the
  editor; the model can then turn them into quick fixes.
* **Apply / clear:** *Quick actions ▾ > Apply all quick fixes* applies all fixes
  of the assistant (bottom to top, skipping any whose text you changed meanwhile);
  *Clear markers* removes them. The model can apply fixes too (`apply_quick_fixes`,
  with approval), but only when you ask for it.
* Quick fixes replace either an exact text on a line (`match`) or whole lines
  (`line`..`end_line`).
* Direct actions (no model call): *Check Java code now* (compile & mark),
  *Apply all quick fixes*, *Clear markers*, *Test markers*.
* Older SWTImageJ versions without the marker API: the welcome text says so
  (with the reason); everything else works as before.

### Troubleshooting markers

1. Check the chat window title: it shows the plugin version (e.g. *LLM Assistant 2.4.1*).
   An older number means an old copy is loaded – the welcome text warns if the
   plugin is installed more than once. Keep one jar and **restart SWTImageJ**.
2. The welcome text reports *Editor markers: available* or the reason why not
   (e.g. `NoSuchMethodException: …addMarker…` = SWTImageJ build older than the
   marker API).
3. *Quick actions ▾ > Test markers* places an info marker on the first line of
   the target editor – if it appears, markers work; double-click removes it.
4. Markers go to the editor shown under *Editor* (Auto = selected Script Explorer
   tab). The transcript shows each tool call (`tool: mark_issues(...)`); if the
   model never calls it, try another model or the direct actions above.

## Installation

1. Copy `LLM_Assistant.jar` into the SWTImageJ `plugins` folder, or use
   **Plugins > Install...** and select the jar.
2. Restart SWTImageJ (or run *Help > Refresh Menus*).
3. Open **Plugins > LLM Assistant > General Preferences...**, enter your API key and
   click *Test connection*.
4. Open **Plugins > LLM Assistant > Chat...**. The **⚙** button (top right)
   opens *General Preferences...* and *Documents...*.

Requires Java 21 (as SWTImageJ itself). No additional libraries.

## Building from source

```bash
./build.sh /path/to/SWTImageJ          # finds the SWTImageJ and SWT jars
# or
CP="/path/org.eclipse.swt.imagej.jar:/path/org.eclipse.swt.gtk.linux.x86_64.jar" ./build.sh
```

In Eclipse you can instead create a plug-in project like the
`IJPluginWindowBuilderExample` in the SWTImageJ repository
(`Require-Bundle: org.eclipse.swt, org.eclipse.swt.imagej`) and copy `src/` into it.

## General Preferences (⚙ menu)

| Setting | Meaning |
|---|---|
| API keys | Several named keys (**Add… / Edit… / Remove**); the selected one is used for all requests. The key may be **empty** for LM Studio, Ollama and other local servers (e.g. an entry "LM Studio" with server `http://127.0.0.1:1234/v1`). A key can have its own server (Base URL), so selecting e.g. "OpenRouter" switches key and server together. The `OPENAI_API_KEY` environment variable can be selected as well. Keys from earlier versions are kept as "Default". Stored in `LLM_Assistant.properties` in the ImageJ preferences folder (owner read/write only on Linux/macOS). |
| Base URL | `https://api.openai.com/v1`, or e.g. `http://127.0.0.1:1234/v1` (LM Studio), `http://localhost:11434/v1` (Ollama). If the URL has no path, `/v1` is appended automatically. Local servers need no API key. |
| Default model | Typed or fetched from the server with *Fetch models*. |
| Temperature | Empty = server default (some reasoning models reject a temperature). |
| Timeout / Max tool rounds | Request timeout and how many tool-call steps the model may take per answer. |
| Use the Script Explorer | Selected tab = target editor, new code in new tabs (default on). |
| Show errors as markers | Marks compiler/runtime errors of editor runs and checks in the editor (default on). |
| Tool use | Lets the model call the SWTImageJ tools below. Disable for models without function calling. |
| Confirmations | Ask before running code and before replacing the whole editor content. |
| Attach defaults | Whether image info and editor code are attached to each message by default. |
| Allow image snapshots | Enables `view_image`, *Attach snapshot* and *Analyze image* (default on). |
| Attach snapshot by default | Pre-ticks *Attach snapshot* (default off, to save tokens). |
| Max. snapshot size | Longest side in pixels of sent images (default 1024). |
| Additional instructions | Appended to the system prompt. |

## Chat window

* **Model** – select or type a model; ↻ fetches the list from the server.
* **Editor** – the editor the assistant works with. *Auto* uses the selected
  Script Explorer tab (or the last used editor window).
* **Script Explorer** – opens / shows the Script Explorer.
* **Write** – preferred language for generated code (ImageJ macro, JavaScript,
  BeanShell, Python).
* **Code** – every code block from the conversation; *Insert*, *Replace*,
  *New tab* (Script Explorer) / *New editor*, *Run*, *Copy*. If a run fails, the error is put into the input
  box so you can send it back for a fix.
* **Attach ▾** – drop-down menu (next to *Quick actions*) with what is sent
  along with each message: *Attach image info*, *Attach snapshot of the active
  image*, *Attach editor code*, *Use documents*. The button shows how many are
  on, e.g. *Attach (2) ▾*; the tooltip lists them. Defaults come from the
  General Preferences.
* **Quick actions ▾** – drop-down menu of predefined prompts:
  *Editor code* (Explain code, Explain selection, Fix / improve, Add comments,
  Convert to Java plugin), *Markers & quick fixes* (Find errors & add quick
  fixes, Apply all quick fixes, Clear markers) and *Active image* (Describe image, Analyze image –
  sends a snapshot, Write analysis macro).
* **Clear** starts a new conversation: all messages, the start text and the
  code blocks are removed, and the model forgets the previous conversation.
* **Activity indicator:** while a request runs, an animated spinner next to the
  status text shows what is happening and for how long – *Waiting for model… 12 s*,
  *Searching documents…*, *Running tool: run_code… 3 s*, *Waiting for your
  approval…* (with the total time for longer multi-step requests). It also runs
  for code started with the *Run* button and stops when the work is done,
  failed or was stopped.
* **Enter** sends, **Shift+Enter** adds a new line, **Stop** cancels the request
  or a running macro. Message history and input area have the same size
  (the divider can be dragged).

## Tools available to the model

| Tool | Does |
|---|---|
| `get_imagej_context` | Version, open images, active image, open editors, Results table size |
| `get_active_image_info` | Dimensions, type, calibration, ROI, statistics, image info |
| `view_image` | Sends a rendered snapshot of an open image to the model (vision) |
| `open_image` | Opens a file, URL or sample image and makes it active |
| `list_sample_images` | Names of the File > Open Samples images |
| `select_image` | Makes an open image the active image |
| `get_editor_text` | Title, language, selection and full text of the target editor (selected Script Explorer tab) |
| `set_editor_text` | `replace`, `insert`, `append` or `new` (new Script Explorer tab) |
| `mark_issues` | Places error/warning/info markers with optional quick fixes in the editor |
| `check_code` | Compiles the editor's Java code without running it and marks the problems |
| `apply_quick_fixes`, `clear_markers` | Applies the assistant's quick fixes / removes markers |
| `search_documents`, `list_documents` | Searches the reference documents / lists them |
| `list_source_files`, `search_source`, `read_source_file`, `find_symbol` | Browse/grep/read files directly in document entries checked for it (⚙ > Documents...) |
| `list_scripts` | Script files in the Script Explorer folders (plugins, macros), with filter |
| `read_script` | Reads one of these files without opening it |
| `open_script` | Opens a file in a Script Explorer tab and makes it the target |
| `run_code` / `run_editor` | Runs macro, Java, or script code; returns result, compiler/runtime errors, new Log output, new images |
| `save_java_plugin` | Compiles and installs a Java plugin class into the plugins folder |
| `search_commands` | Searches menu command names for `run("...")` |
| `get_log`, `get_results_table` | Log window and Results/other tables |

Macro errors are captured (`Interpreter.setIgnoreErrors`) and returned to the
model instead of opening an error dialog, so it can correct its own code.

## Notes

* JavaScript uses ImageJ's Nashorn-based runner. Java 21 does not ship Nashorn,
  so add the standalone Nashorn jar or prefer the macro language.
  Python and BeanShell use ImageJ's usual downloadable interpreter plugins.
* What goes to the server: your text, attached image info / editor code, tool
  results, and image snapshots only when you attach them or the model calls
  `view_image` (disable in the preferences to prevent any image upload).
* Programmatic use from a macro:
  `call("llmassistant.ChatWindow.ask", "Count the nuclei in the active image");`

## Files

```
src/plugins.config                    menu entries
src/llmassistant/LLM_Assistant.java   plugin entry point
src/llmassistant/ChatWindow.java      chat UI and tool-call loop
src/llmassistant/PreferencesDialog.java
src/llmassistant/LLMSettings.java     persistent settings
src/llmassistant/OpenAIClient.java    /models and /chat/completions client
src/llmassistant/ImageJTools.java     tool definitions, context, code runner
src/llmassistant/JavaRunner.java      in-memory Java compiler/runner and plugin installer
src/llmassistant/KnowledgeBase.java   reference documents: loading, excerpts, BM25 search
src/llmassistant/DocumentsDialog.java managing documents, semantic search options, test search
src/llmassistant/EmbeddingCache.java  on-disk cache of embeddings
src/llmassistant/BusyIndicator.java   animated activity spinner with phase and elapsed time
src/llmassistant/Icons.java           icons drawn in code (gear)
src/llmassistant/EditorMarkers.java   markers and quick fixes (line/text addressing, apply all)
src/llmassistant/MarkerSupport.java   direct calls to the Editor marker API (loaded only if present)
src/llmassistant/EditorBridge.java    thread-safe access to Script Explorer tabs and editors
src/llmassistant/Json.java            dependency-free JSON
```

## License

Licensed under the Apache License, Version 2.0 – see [LICENSE](LICENSE).
