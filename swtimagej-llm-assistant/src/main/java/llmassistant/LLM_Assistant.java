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

import ij.plugin.PlugIn;

/**
 * SWTImageJ plugin entry point.
 * <ul>
 * <li>arg "" or "chat": opens the chat window</li>
 * <li>arg "prefs": opens the preferences dialog</li>
 * </ul>
 * Menu entries are defined in plugins.config inside the jar.
 */
public class LLM_Assistant implements PlugIn {

	/** Shown in the chat window title and welcome text, to verify which build is running. */
	public static final String VERSION = "2.4.1 (2026-10-02)";

	@Override
	public void run(String arg) {
		if("prefs".equalsIgnoreCase(arg) || "preferences".equalsIgnoreCase(arg))
			PreferencesDialog.open(null, null);
		else if("documents".equalsIgnoreCase(arg))
			DocumentsDialog.show(null, null);
		else
			ChatWindow.showWindow();
	}
}
