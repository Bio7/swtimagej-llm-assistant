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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Minimal client for OpenAI-compatible REST APIs (OpenAI, Azure-compatible
 * gateways, Ollama, LM Studio, vLLM, OpenRouter, ... anything exposing
 * /v1/chat/completions and /v1/models).
 */
public class OpenAIClient {

	private final HttpClient http;
	private final LLMSettings settings;
	private volatile CompletableFuture<HttpResponse<String>> running;

	public OpenAIClient(LLMSettings settings) {
		this.settings = settings;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	/** Thrown for HTTP / API errors, carries a readable message. */
	public static class ApiException extends Exception {

		private static final long serialVersionUID = 1L;

		public ApiException(String msg) {
			super(msg);
		}
	}

	private String base() {
		return normalizeBaseUrl(settings.baseUrl);
	}

	/**
	 * Normalizes the base URL. A URL without a path (e.g. http://127.0.0.1:1234
	 * for LM Studio or http://localhost:11434 for Ollama) gets "/v1" appended,
	 * because OpenAI-compatible servers serve their API below /v1.
	 */
	public static String normalizeBaseUrl(String url) {
		String b = url == null || url.isBlank() ? LLMSettings.DEFAULT_BASE_URL : url.trim();
		if(!b.matches("(?i)^https?://.*"))
			b = "http://" + b;
		while(b.endsWith("/"))
			b = b.substring(0, b.length() - 1);
		try {
			String path = URI.create(b).getPath();
			if(path == null || path.isEmpty())
				b = b + "/v1";
		} catch(IllegalArgumentException ignored) {
			// leave as typed, the request will report the problem
		}
		return b;
	}

	private HttpRequest.Builder request(String path) throws ApiException {
		return request(base(), path);
	}

	/** Base URL for embeddings: the separate embedding server if configured, else the chat server. */
	private String embeddingBase() {
		String e = settings.embeddingBaseUrl;
		return e == null || e.isBlank() ? base() : normalizeBaseUrl(e);
	}

	private HttpRequest.Builder request(String baseUrl, String path) throws ApiException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(Math.max(10, settings.timeoutSeconds))).header("Content-Type", "application/json").header("Accept", "application/json");
		String key = settings.effectiveApiKey();
		boolean openAi = baseUrl.contains("api.openai.com");
		if(key.isEmpty() && openAi)
			throw new ApiException("No API key configured. Open \u2699 > General Preferences... (or Plugins > LLM Assistant > General Preferences...) or set OPENAI_API_KEY.");
		if(!key.isEmpty())
			b.header("Authorization", "Bearer " + key);
		return b;
	}

	/** Cancels the request currently in flight (if any). */
	public void cancel() {
		CompletableFuture<HttpResponse<String>> f = running;
		if(f != null)
			f.cancel(true);
	}

	private String execute(HttpRequest req) throws ApiException, InterruptedException {
		CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		running = f;
		HttpResponse<String> resp;
		try {
			resp = f.get();
		} catch(CancellationException e) {
			throw new InterruptedException("Request cancelled");
		} catch(ExecutionException e) {
			Throwable c = e.getCause() == null ? e : e.getCause();
			throw new ApiException("Connection failed: " + c.getClass().getSimpleName() + (c.getMessage() != null ? " - " + c.getMessage() : ""));
		} finally {
			running = null;
		}
		String body = resp.body();
		if(resp.statusCode() / 100 != 2)
			throw new ApiException("HTTP " + resp.statusCode() + ": " + extractError(body));
		// Some local servers (e.g. LM Studio) answer unknown endpoints with HTTP 200 and an error body
		Object parsed;
		try {
			parsed = Json.parse(body);
		} catch(Exception e) {
			throw new ApiException("The server at " + base() + " did not return JSON. Check the Base URL (it usually ends with /v1). Response: " + extractError(body));
		}
		Map<String, Object> m = Json.asMap(parsed);
		if(m != null && m.get("error") != null && m.get("choices") == null && m.get("data") == null)
			throw new ApiException(extractError(body) + "  (URL: " + req.uri() + " - check that the Base URL ends with /v1)");
		return body;
	}

	private static String extractError(String body) {
		try {
			Map<String, Object> m = Json.asMap(Json.parse(body));
			Map<String, Object> e = Json.asMap(m.get("error"));
			if(e != null)
				return Json.str(e, "message", body);
			if(m.get("error") != null)
				return m.get("error").toString();
		} catch(Exception ignored) {
		}
		return body == null ? "" : (body.length() > 500 ? body.substring(0, 500) + "..." : body);
	}

	/** Lists the model ids offered by the endpoint (GET /models). */
	public List<String> listModels() throws ApiException, InterruptedException {
		String body = execute(request("/models").GET().build());
		List<String> ids = new ArrayList<>();
		Map<String, Object> m = Json.asMap(Json.parse(body));
		List<Object> data = m == null ? null : Json.asList(m.get("data"));
		if(data == null && m != null)
			data = Json.asList(m.get("models")); // some servers use "models"
		if(data != null) {
			for(Object o : data) {
				Map<String, Object> mm = Json.asMap(o);
				String id = Json.str(mm, "id", Json.str(mm, "name", null));
				if(id != null && isChatModel(id))
					ids.add(id);
			}
		}
		Collections.sort(ids);
		return ids;
	}

	/** Embedding models offered by the embedding server (ids containing "embed"). */
	public List<String> listEmbeddingModels() throws ApiException, InterruptedException {
		String body = execute(request(embeddingBase(), "/models").GET().build());
		List<String> ids = new ArrayList<>();
		Map<String, Object> m = Json.asMap(Json.parse(body));
		List<Object> data = m == null ? null : Json.asList(m.get("data"));
		if(data != null)
			for(Object o : data) {
				String id = Json.str(Json.asMap(o), "id", null);
				if(id != null && id.toLowerCase().contains("embed"))
					ids.add(id);
			}
		Collections.sort(ids);
		return ids;
	}

	/**
	 * Computes embeddings (POST /embeddings) for the texts, in order. For OpenAI
	 * text-embedding-3 models the vectors are shortened to {@code dimensions} (if
	 * > 0) to save memory; other servers get no dimensions parameter.
	 */
	public List<float[]> embed(String model, List<String> texts, int dimensions) throws ApiException, InterruptedException {
		Map<String, Object> payload = Json.obj("model", model, "input", new ArrayList<Object>(texts));
		if(dimensions > 0 && model.startsWith("text-embedding-3"))
			payload.put("dimensions", dimensions);
		String body = execute(request(embeddingBase(), "/embeddings").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload))).build());
		Map<String, Object> m = Json.asMap(Json.parse(body));
		List<Object> data = m == null ? null : Json.asList(m.get("data"));
		if(data == null || data.size() != texts.size())
			throw new ApiException("Unexpected embeddings response" + (data == null ? ": " + extractError(body) : " (" + data.size() + " vectors for " + texts.size() + " texts)"));
		float[][] out = new float[texts.size()][];
		for(int i = 0; i < data.size(); i++) {
			Map<String, Object> d = Json.asMap(data.get(i));
			Object idx = d.get("index");
			int at = idx instanceof Number ? ((Number)idx).intValue() : i;
			List<Object> vec = Json.asList(d.get("embedding"));
			if(vec == null || at < 0 || at >= out.length)
				throw new ApiException("Unexpected embeddings response (no vector)");
			float[] v = new float[vec.size()];
			for(int k = 0; k < v.length; k++)
				v[k] = ((Number)vec.get(k)).floatValue();
			out[at] = v;
		}
		return java.util.Arrays.asList(out);
	}

	/** Filters out obvious non-chat models (embeddings, audio, image, moderation). */
	private static boolean isChatModel(String id) {
		String s = id.toLowerCase();
		String[] skip = {"embedding", "whisper", "tts", "dall-e", "moderation", "davinci-002", "babbage-002", "transcribe", "gpt-image", "-realtime", "-audio", "sora"};
		for(String k : skip) {
			if(s.contains(k))
				return false;
		}
		return true;
	}

	/**
	 * Sends a chat completion request and returns choices[0].message.
	 *
	 * @param messages
	 *            conversation in OpenAI format
	 * @param tools
	 *            function tool definitions or null
	 */
	public Map<String, Object> chat(String model, List<Map<String, Object>> messages, List<Object> tools) throws ApiException, InterruptedException {
		Map<String, Object> payload = Json.obj("model", model, "messages", messages);
		Double t = settings.temperatureValue();
		if(t != null)
			payload.put("temperature", t);
		if(settings.reasoningEffort != null && !settings.reasoningEffort.isBlank())
			payload.put("reasoning_effort", settings.reasoningEffort.trim());
		if(tools != null && !tools.isEmpty()) {
			payload.put("tools", tools);
			payload.put("tool_choice", "auto");
		}
		HttpRequest req = request("/chat/completions").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload))).build();
		String body = execute(req);
		Map<String, Object> resp = Json.asMap(Json.parse(body));
		List<Object> choices = resp == null ? null : Json.asList(resp.get("choices"));
		if(choices == null || choices.isEmpty())
			throw new ApiException("Unexpected response: " + extractError(body));
		Map<String, Object> msg = Json.asMap(Json.asMap(choices.get(0)).get("message"));
		if(msg == null)
			throw new ApiException("Response contains no message.");
		return msg;
	}
}
