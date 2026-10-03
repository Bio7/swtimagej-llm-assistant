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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

import ij.IJ;
import ij.Prefs;

/**
 * Persistent settings of the LLM Assistant.
 * <p>
 * Stored in a separate file (LLM_Assistant.properties) inside the ImageJ
 * preferences directory instead of IJ_Prefs.txt, so the API key is not mixed
 * into the general ImageJ preferences and can be protected with owner-only file
 * permissions (on POSIX systems). If no key is stored, the environment variable
 * OPENAI_API_KEY is used.
 */
public final class LLMSettings {

	public static final String FILE_NAME = "LLM_Assistant.properties";
	public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	public static final String DEFAULT_MODEL = "gpt-4o-mini";

	private static LLMSettings instance;

	/** A named API key; baseUrl (optional) switches the server together with the key. */
	public static final class ApiKey {

		public String name = "", key = "", baseUrl = "";

		public ApiKey() {
		}

		public ApiKey(String name, String key, String baseUrl) {
			this.name = name == null ? "" : name;
			this.key = key == null ? "" : key;
			this.baseUrl = baseUrl == null ? "" : baseUrl;
		}

		public ApiKey copy() {
			return new ApiKey(name, key, baseUrl);
		}

		/** Key with the middle hidden, e.g. "sk-...3f9a". */
		public String masked() {
			String k = key == null ? "" : key.trim();
			if(k.isEmpty())
				return "no key";
			return k.length() <= 10 ? k.charAt(0) + "..." : k.substring(0, 3) + "..." + k.substring(k.length() - 4);
		}
	}

	/** Pseudo entry: use the OPENAI_API_KEY environment variable. */
	public static final String ENV_KEY = "OPENAI_API_KEY (environment variable)";
	public final List<ApiKey> apiKeys = new ArrayList<>();
	/** Name of the selected key (or ENV_KEY). */
	public String activeKey = "";
	public String baseUrl = DEFAULT_BASE_URL;
	public String model = DEFAULT_MODEL;
	/** Known models (fetched from the server or typed in), comma separated when stored. */
	public List<String> models = new ArrayList<>();
	/** Empty = let the server use its default (some reasoning models reject temperature). */
	public String temperature = "";
	public int timeoutSeconds = 120;
	public int maxToolRounds = 8;
	public boolean enableTools = true;
	public boolean confirmRun = true;
	public boolean confirmEditorReplace = true;
	public boolean attachImageInfo = true;
	public boolean attachEditorText = true;
	/** Allow sending image snapshots to the model (needs a vision-capable model). */
	public boolean enableVision = true;
	/** Attach a snapshot of the active image to each message by default. */
	public boolean attachSnapshot = false;
	/** Longest side of snapshots in pixels. */
	public int snapshotMaxSize = 1024;
	public String defaultLanguage = "ijm";
	/** Use the Script Explorer (tabs) as the default place to read and write code. */
	public boolean useScriptExplorer = true;
	/** Show compiler/runtime errors of editor runs and checks as markers in the editor. */
	public boolean markErrors = true;
	/** Reference documents: files or folders. */
	public List<String> documents = new ArrayList<>();
	/** Include the built-in ImageJ macro functions reference. */
	public boolean useBuiltinReference = true;
	/** Attach matching document excerpts to each message. */
	public boolean attachDocuments = true;
	public int docTopK = 4;
	public int docMaxChars = 6000;
	/** Hybrid search: BM25 + embeddings (reciprocal rank fusion). */
	public boolean semanticSearch = false;
	public String embeddingModel = "text-embedding-3-small";
	/** Separate server for embeddings (empty = chat server). */
	public String embeddingBaseUrl = "";
	/** Vector size for OpenAI text-embedding-3 models (smaller = less memory). */
	public int embeddingDimensions = 512;
	public String extraInstructions = "";

	private LLMSettings() {
	}

	public static synchronized LLMSettings get() {
		if(instance == null) {
			instance = new LLMSettings();
			instance.load();
		}
		return instance;
	}

	/** The key to use: stored key or, as fallback, the OPENAI_API_KEY environment variable. */
	public static String environmentKey() {
		String env = System.getenv("OPENAI_API_KEY");
		return env == null ? "" : env.trim();
	}

	/** The selected key entry, the first one if the selection is unknown, or null (environment / none). */
	public ApiKey activeApiKey() {
		if(ENV_KEY.equals(activeKey))
			return null;
		for(ApiKey k : apiKeys)
			if(k.name.equals(activeKey))
				return k;
		return apiKeys.isEmpty() ? null : apiKeys.get(0);
	}

	/** The key to use: the selected entry, or the OPENAI_API_KEY environment variable. */
	public String effectiveApiKey() {
		ApiKey k = activeApiKey();
		if(k != null && !k.key.isBlank())
			return k.key.trim();
		return k == null ? environmentKey() : "";
	}

	public boolean hasKeyFromEnvironment() {
		return activeApiKey() == null && !environmentKey().isEmpty();
	}

	/** Display name of the key in use. */
	public String activeKeyLabel() {
		ApiKey k = activeApiKey();
		if(k != null)
			return "\"" + k.name + "\" (" + k.masked() + ")";
		return environmentKey().isEmpty() ? "none" : "OPENAI_API_KEY environment variable";
	}

	/** Selects a key by name (or ENV_KEY); a key with its own base URL also switches the server. */
	public void selectKey(String name) {
		activeKey = name == null ? "" : name;
		ApiKey k = activeApiKey();
		if(k != null && k.baseUrl != null && !k.baseUrl.isBlank())
			baseUrl = k.baseUrl.trim();
	}

	public static File settingsFile() {
		String dir = null;
		try {
			dir = Prefs.getPrefsDir();
		} catch(Throwable t) {
			// ignore, fall back to home
		}
		if(dir == null)
			dir = System.getProperty("user.home");
		return new File(dir, FILE_NAME);
	}

	public void load() {
		File f = settingsFile();
		if(!f.exists())
			return;
		Properties p = new Properties();
		try(InputStream in = new FileInputStream(f)) {
			p.load(in);
		} catch(Exception e) {
			IJ.log("LLM Assistant: could not read settings: " + e.getMessage());
			return;
		}
		apiKeys.clear();
		String keys = p.getProperty("apiKeys");
		if(keys != null) {
			for(String line : keys.split("\n")) {
				String[] parts = line.split("\t", -1);
				if(parts.length >= 3 && !parts[0].isBlank())
					apiKeys.add(new ApiKey(parts[0], parts[2], parts[1]));
			}
		} else {
			String old = p.getProperty("apiKey", ""); // single key of earlier versions
			if(!old.isBlank())
				apiKeys.add(new ApiKey("Default", old.trim(), ""));
		}
		activeKey = p.getProperty("activeKey", apiKeys.isEmpty() ? "" : apiKeys.get(0).name);
		baseUrl = p.getProperty("baseUrl", baseUrl);
		model = p.getProperty("model", model);
		temperature = p.getProperty("temperature", temperature);
		timeoutSeconds = parseInt(p.getProperty("timeoutSeconds"), timeoutSeconds);
		maxToolRounds = parseInt(p.getProperty("maxToolRounds"), maxToolRounds);
		enableTools = Boolean.parseBoolean(p.getProperty("enableTools", "" + enableTools));
		confirmRun = Boolean.parseBoolean(p.getProperty("confirmRun", "" + confirmRun));
		confirmEditorReplace = Boolean.parseBoolean(p.getProperty("confirmEditorReplace", "" + confirmEditorReplace));
		attachImageInfo = Boolean.parseBoolean(p.getProperty("attachImageInfo", "" + attachImageInfo));
		attachEditorText = Boolean.parseBoolean(p.getProperty("attachEditorText", "" + attachEditorText));
		defaultLanguage = p.getProperty("defaultLanguage", defaultLanguage);
		enableVision = Boolean.parseBoolean(p.getProperty("enableVision", "" + enableVision));
		attachSnapshot = Boolean.parseBoolean(p.getProperty("attachSnapshot", "" + attachSnapshot));
		snapshotMaxSize = parseInt(p.getProperty("snapshotMaxSize"), snapshotMaxSize);
		useScriptExplorer = Boolean.parseBoolean(p.getProperty("useScriptExplorer", "" + useScriptExplorer));
		markErrors = Boolean.parseBoolean(p.getProperty("markErrors", "" + markErrors));
		useBuiltinReference = Boolean.parseBoolean(p.getProperty("useBuiltinReference", "" + useBuiltinReference));
		attachDocuments = Boolean.parseBoolean(p.getProperty("attachDocuments", "" + attachDocuments));
		docTopK = parseInt(p.getProperty("docTopK"), docTopK);
		docMaxChars = parseInt(p.getProperty("docMaxChars"), docMaxChars);
		semanticSearch = Boolean.parseBoolean(p.getProperty("semanticSearch", "" + semanticSearch));
		embeddingModel = p.getProperty("embeddingModel", embeddingModel);
		embeddingBaseUrl = p.getProperty("embeddingBaseUrl", embeddingBaseUrl);
		embeddingDimensions = parseInt(p.getProperty("embeddingDimensions"), embeddingDimensions);
		documents.clear();
		for(String d : p.getProperty("documents", "").split("\n"))
			if(!d.isBlank())
				documents.add(d.trim());
		extraInstructions = p.getProperty("extraInstructions", extraInstructions);
		models.clear();
		for(String m : p.getProperty("models", "").split(",")) {
			if(!m.isBlank())
				models.add(m.trim());
		}
	}

	public void save() {
		Properties p = new Properties();
		StringBuilder keys = new StringBuilder();
		for(ApiKey k : apiKeys) // name <TAB> base URL <TAB> key, one per line
			keys.append(clean(k.name)).append('\t').append(clean(k.baseUrl)).append('\t').append(clean(k.key)).append('\n');
		p.setProperty("apiKeys", keys.toString().strip());
		p.setProperty("activeKey", activeKey == null ? "" : activeKey);
		p.setProperty("baseUrl", baseUrl);
		p.setProperty("model", model);
		p.setProperty("models", String.join(",", models));
		p.setProperty("temperature", temperature == null ? "" : temperature.trim());
		p.setProperty("timeoutSeconds", "" + timeoutSeconds);
		p.setProperty("maxToolRounds", "" + maxToolRounds);
		p.setProperty("enableTools", "" + enableTools);
		p.setProperty("confirmRun", "" + confirmRun);
		p.setProperty("confirmEditorReplace", "" + confirmEditorReplace);
		p.setProperty("attachImageInfo", "" + attachImageInfo);
		p.setProperty("attachEditorText", "" + attachEditorText);
		p.setProperty("defaultLanguage", defaultLanguage);
		p.setProperty("enableVision", "" + enableVision);
		p.setProperty("attachSnapshot", "" + attachSnapshot);
		p.setProperty("snapshotMaxSize", "" + snapshotMaxSize);
		p.setProperty("useScriptExplorer", "" + useScriptExplorer);
		p.setProperty("markErrors", "" + markErrors);
		p.setProperty("useBuiltinReference", "" + useBuiltinReference);
		p.setProperty("attachDocuments", "" + attachDocuments);
		p.setProperty("docTopK", "" + docTopK);
		p.setProperty("docMaxChars", "" + docMaxChars);
		p.setProperty("semanticSearch", "" + semanticSearch);
		p.setProperty("embeddingModel", embeddingModel);
		p.setProperty("embeddingBaseUrl", embeddingBaseUrl == null ? "" : embeddingBaseUrl);
		p.setProperty("embeddingDimensions", "" + embeddingDimensions);
		p.setProperty("documents", String.join("\n", documents));
		p.setProperty("extraInstructions", extraInstructions == null ? "" : extraInstructions);
		File f = settingsFile();
		try {
			File parent = f.getParentFile();
			if(parent != null && !parent.exists())
				parent.mkdirs();
			try(OutputStream out = new FileOutputStream(f)) {
				p.store(out, "SWTImageJ LLM Assistant settings (contains your API key - keep private)");
			}
			restrictPermissions(f);
		} catch(Exception e) {
			IJ.error("LLM Assistant", "Could not save settings:\n" + e.getMessage());
		}
	}

	private static void restrictPermissions(File f) {
		try {
			Files.setPosixFilePermissions(f.toPath(), EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch(UnsupportedOperationException | java.io.IOException e) {
			// Windows: not a POSIX file system, rely on the user profile ACLs.
		}
	}

	private static String clean(String s) {
		return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
	}

	public Double temperatureValue() {
		return temperatureValueOf(temperature);
	}

	public Double temperatureValueOf(String t) {
		if(t == null || t.isBlank())
			return null;
		try {
			return Double.parseDouble(t.trim());
		} catch(NumberFormatException e) {
			return null;
		}
	}

	public void rememberModel(String m) {
		if(m == null || m.isBlank())
			return;
		if(!models.contains(m.trim()))
			models.add(0, m.trim());
	}

	private static int parseInt(String s, int def) {
		try {
			return s == null ? def : Integer.parseInt(s.trim());
		} catch(NumberFormatException e) {
			return def;
		}
	}
}
