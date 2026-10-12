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

/**
 * An editor in which the assistant places markers with quick fixes: an
 * SWTImageJ editor ({@link ImageJMarkerHost}) or an editor of the embedding
 * application which supports the ij.swt.CodeEditor marker API
 * ({@link ExternalMarkerHost}). Get one with {@link CodeTarget#markerHost()}.
 */
abstract class MarkerHost {

	final CodeTarget target;

	MarkerHost(CodeTarget target) {
		this.target = target;
	}

	/** The object which identifies the editor (two hosts for the same editor have equal keys). */
	abstract Object key();

	/**
	 * Adds a marker for the range [start, end) and returns the handle which
	 * identifies it for {@link #apply} and {@link #pending} (may be null if there
	 * is no quick fix).
	 */
	abstract Object add(int start, int end, String severity, String message, String fixDescription, String replacement);

	/** Removes the markers the assistant placed. */
	abstract void clear();

	/** Applies the quick fix of the marker if it is still present. */
	abstract boolean apply(int start, int end, Object handle);

	/** True if the quick fix of the marker can still be applied. */
	abstract boolean pending(int start, int end, Object handle);
}
