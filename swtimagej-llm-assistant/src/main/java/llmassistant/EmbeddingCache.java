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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Embeddings cached on disk, one file per model + server + dimensions, keyed by
 * the SHA-1 of the embedded text. Unchanged excerpts are never embedded twice
 * (saves time and, with OpenAI, cost).
 */
final class EmbeddingCache {

	private static final int MAGIC = 0x4C4C4D45; // "LLME"
	private final File file;
	private final Map<String, float[]> map = new HashMap<>();
	private boolean dirty;

	private EmbeddingCache(File file) {
		this.file = file;
	}

	static File directory() {
		File dir = new File(LLMSettings.settingsFile().getParentFile(), "LLM_Assistant_embeddings");
		dir.mkdirs();
		return dir;
	}

	static EmbeddingCache open(String model, String server, int dimensions) {
		String name = model.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + key(OpenAIClient.normalizeBaseUrl(server) + "|" + dimensions).substring(0, 10) + ".vec";
		EmbeddingCache c = new EmbeddingCache(new File(directory(), name));
		c.load();
		return c;
	}

	static String key(String text) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for(byte b : d)
				sb.append(String.format("%02x", b));
			return sb.toString();
		} catch(Exception e) {
			return Integer.toHexString(text.hashCode());
		}
	}

	float[] get(String key) {
		return map.get(key);
	}

	void put(String key, float[] v) {
		map.put(key, v);
		dirty = true;
	}

	private void load() {
		if(!file.isFile())
			return;
		try(DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			if(in.readInt() != MAGIC)
				return;
			int n = in.readInt();
			for(int i = 0; i < n; i++) {
				String k = in.readUTF();
				int dim = in.readInt();
				float[] v = new float[dim];
				for(int j = 0; j < dim; j++)
					v[j] = in.readFloat();
				map.put(k, v);
			}
		} catch(Exception e) {
			map.clear(); // corrupt cache: rebuild
		}
	}

	/** Writes the cache; with keep != null, entries of excerpts no longer indexed are dropped. */
	void save(Set<String> keep) {
		if(keep != null) {
			for(Iterator<String> it = map.keySet().iterator(); it.hasNext();)
				if(!keep.contains(it.next())) {
					it.remove();
					dirty = true;
				}
		}
		if(!dirty)
			return;
		File tmp = new File(file.getPath() + ".tmp");
		try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
			out.writeInt(MAGIC);
			out.writeInt(map.size());
			for(Map.Entry<String, float[]> e : map.entrySet()) {
				out.writeUTF(e.getKey());
				out.writeInt(e.getValue().length);
				for(float f : e.getValue())
					out.writeFloat(f);
			}
		} catch(Exception e) {
			tmp.delete();
			return;
		}
		file.delete();
		if(tmp.renameTo(file))
			dirty = false;
	}
}
