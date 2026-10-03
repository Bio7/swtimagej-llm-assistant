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

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

/**
 * Icons drawn in code, so they need no image files, stay sharp at every
 * display scaling (ImageDataProvider renders per zoom level) and use the
 * theme's text colour (light and dark themes).
 */
final class Icons {

	private Icons() {
	}

	/** A gear ("Zahnrad") icon, 16 px at 100% zoom; the caller disposes it. */
	static Image gear(Display display, RGB color) {
		return new Image(display, (ImageDataProvider)zoom -> gearData(Math.max(16, 16 * zoom / 100), color));
	}

	/**
	 * Gear with 8 teeth and a hole, anti-aliased by 4x4 supersampling; the
	 * transparency is stored in the alpha channel.
	 */
	static ImageData gearData(int size, RGB color) {
		ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
		byte[] alpha = new byte[size * size];
		int pixel = data.palette.getPixel(color);
		double c = size / 2.0;
		int teeth = 8, ss = 4;
		double rOuter = 0.47, rBody = 0.34, rHole = 0.14; // as fractions of the size
		for(int y = 0; y < size; y++) {
			for(int x = 0; x < size; x++) {
				int hits = 0;
				for(int sy = 0; sy < ss; sy++) {
					for(int sx = 0; sx < ss; sx++) {
						double px = (x + (sx + 0.5) / ss - c) / size;
						double py = (y + (sy + 0.5) / ss - c) / size;
						double r = Math.hypot(px, py);
						double a = Math.atan2(py, px);
						// tooth profile: plateau of +/- 22% of the tooth pitch, with sloped flanks
						double t = (a / (2 * Math.PI) * teeth) % 1.0;
						if(t < 0)
							t += 1;
						double d = Math.abs(t - 0.5); // 0 = tooth centre, 0.5 = gap centre
						double edge = d < 0.22 ? rOuter : d < 0.32 ? rOuter - (d - 0.22) / 0.10 * (rOuter - rBody) : rBody;
						if(r <= edge && r >= rHole)
							hits++;
					}
				}
				data.setPixel(x, y, pixel);
				alpha[y * size + x] = (byte)(255 * hits / (ss * ss));
			}
		}
		data.alphaData = alpha;
		return data;
	}
}
