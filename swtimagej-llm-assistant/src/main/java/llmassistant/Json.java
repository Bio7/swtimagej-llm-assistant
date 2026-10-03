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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON parser / serializer.
 * Objects map to LinkedHashMap&lt;String,Object&gt;, arrays to ArrayList&lt;Object&gt;,
 * numbers to Double (or Long when integral), plus String, Boolean and null.
 */
public final class Json {

	private final String s;
	private int pos;

	private Json(String s) {
		this.s = s;
	}

	/* ------------------------------------------------------------ parsing */

	public static Object parse(String text) {
		Json p = new Json(text);
		p.ws();
		Object v = p.value();
		p.ws();
		if(p.pos != p.s.length())
			throw p.err("Trailing characters");
		return v;
	}

	private RuntimeException err(String msg) {
		return new IllegalArgumentException("JSON: " + msg + " at position " + pos);
	}

	private void ws() {
		while(pos < s.length() && Character.isWhitespace(s.charAt(pos)))
			pos++;
	}

	private Object value() {
		if(pos >= s.length())
			throw err("Unexpected end");
		char c = s.charAt(pos);
		switch(c) {
			case '{':
				return object();
			case '[':
				return array();
			case '"':
				return string();
			case 't':
				expect("true");
				return Boolean.TRUE;
			case 'f':
				expect("false");
				return Boolean.FALSE;
			case 'n':
				expect("null");
				return null;
			default:
				return number();
		}
	}

	private void expect(String word) {
		if(!s.startsWith(word, pos))
			throw err("Expected " + word);
		pos += word.length();
	}

	private Map<String, Object> object() {
		Map<String, Object> m = new LinkedHashMap<>();
		pos++; // {
		ws();
		if(peek() == '}') {
			pos++;
			return m;
		}
		while(true) {
			ws();
			if(peek() != '"')
				throw err("Expected key");
			String k = string();
			ws();
			if(peek() != ':')
				throw err("Expected ':'");
			pos++;
			ws();
			m.put(k, value());
			ws();
			char c = peek();
			pos++;
			if(c == '}')
				return m;
			if(c != ',')
				throw err("Expected ',' or '}'");
		}
	}

	private List<Object> array() {
		List<Object> l = new ArrayList<>();
		pos++; // [
		ws();
		if(peek() == ']') {
			pos++;
			return l;
		}
		while(true) {
			ws();
			l.add(value());
			ws();
			char c = peek();
			pos++;
			if(c == ']')
				return l;
			if(c != ',')
				throw err("Expected ',' or ']'");
		}
	}

	private char peek() {
		if(pos >= s.length())
			throw err("Unexpected end");
		return s.charAt(pos);
	}

	private String string() {
		pos++; // opening quote
		StringBuilder sb = new StringBuilder();
		while(true) {
			if(pos >= s.length())
				throw err("Unterminated string");
			char c = s.charAt(pos++);
			if(c == '"')
				return sb.toString();
			if(c != '\\') {
				sb.append(c);
				continue;
			}
			char e = s.charAt(pos++);
			switch(e) {
				case '"':
				case '\\':
				case '/':
					sb.append(e);
					break;
				case 'b':
					sb.append('\b');
					break;
				case 'f':
					sb.append('\f');
					break;
				case 'n':
					sb.append('\n');
					break;
				case 'r':
					sb.append('\r');
					break;
				case 't':
					sb.append('\t');
					break;
				case 'u':
					sb.append((char)Integer.parseInt(s.substring(pos, pos + 4), 16));
					pos += 4;
					break;
				default:
					throw err("Bad escape");
			}
		}
	}

	private Object number() {
		int start = pos;
		while(pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0)
			pos++;
		String n = s.substring(start, pos);
		if(n.isEmpty())
			throw err("Unexpected character '" + s.charAt(start) + "'");
		if(n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
			try {
				return Long.parseLong(n);
			} catch(NumberFormatException ignored) {
			}
		}
		return Double.parseDouble(n);
	}

	/* ------------------------------------------------------------ writing */

	public static String stringify(Object o) {
		StringBuilder sb = new StringBuilder();
		write(sb, o);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder sb, Object o) {
		if(o == null) {
			sb.append("null");
		} else if(o instanceof String) {
			quote(sb, (String)o);
		} else if(o instanceof Number || o instanceof Boolean) {
			sb.append(o.toString());
		} else if(o instanceof Map) {
			sb.append('{');
			Iterator<Map.Entry<Object, Object>> it = ((Map<Object, Object>)o).entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<Object, Object> e = it.next();
				quote(sb, String.valueOf(e.getKey()));
				sb.append(':');
				write(sb, e.getValue());
				if(it.hasNext())
					sb.append(',');
			}
			sb.append('}');
		} else if(o instanceof Iterable) {
			sb.append('[');
			Iterator<Object> it = ((Iterable<Object>)o).iterator();
			while(it.hasNext()) {
				write(sb, it.next());
				if(it.hasNext())
					sb.append(',');
			}
			sb.append(']');
		} else if(o instanceof Object[]) {
			write(sb, java.util.Arrays.asList((Object[])o));
		} else {
			quote(sb, o.toString());
		}
	}

	private static void quote(StringBuilder sb, String str) {
		sb.append('"');
		for(int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			switch(c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if(c < 0x20)
						sb.append(String.format("\\u%04x", (int)c));
					else
						sb.append(c);
			}
		}
		sb.append('"');
	}

	/* ------------------------------------------------------------ helpers */

	/** Convenience builder: Json.obj("k1", v1, "k2", v2, ...). */
	public static Map<String, Object> obj(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2)
			m.put((String)kv[i], kv[i + 1]);
		return m;
	}

	public static List<Object> arr(Object... items) {
		List<Object> l = new ArrayList<>();
		for(Object o : items)
			l.add(o);
		return l;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object o) {
		return o instanceof Map ? (Map<String, Object>)o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object o) {
		return o instanceof List ? (List<Object>)o : null;
	}

	public static String str(Map<String, Object> m, String key, String def) {
		if(m == null)
			return def;
		Object v = m.get(key);
		return v == null ? def : v.toString();
	}
}
