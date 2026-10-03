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

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * Animated activity indicator: a rotating arc (drawn with anti-aliasing in the
 * theme's colours) and a text with the current phase and the elapsed time,
 * e.g. "Waiting for model... 12 s". Hidden when idle. All methods must be
 * called on the UI thread; {@link #setPhase} is also safe to call via
 * Display.asyncExec from worker threads.
 */
final class BusyIndicator {

	private static final int FRAME_MS = 60, SIZE = 16;
	private final Canvas canvas;
	private final Label label;
	private boolean running;
	private int angle;
	private long started, phaseStarted;
	private String phase = "";
	private final Runnable tick = this::tick;

	BusyIndicator(Composite parent, Label label) {
		this.label = label;
		canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED);
		GridData gd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
		gd.widthHint = gd.heightHint = SIZE;
		canvas.setLayoutData(gd);
		canvas.addListener(SWT.Paint, e -> paint(e.gc));
		canvas.setVisible(false);
		canvas.setToolTipText("The request is running - press Stop to cancel");
	}

	/** Puts the spinner directly before the status label. */
	void placeBefore(org.eclipse.swt.widgets.Control c) {
		canvas.moveAbove(c);
	}

	void start(String firstPhase) {
		running = true;
		started = phaseStarted = System.currentTimeMillis();
		phase = firstPhase;
		canvas.setVisible(true);
		update();
		canvas.getDisplay().timerExec(FRAME_MS, tick);
	}

	/** Changes the shown activity (e.g. "Running tool: run_code"); the time of the phase restarts. */
	void setPhase(String p) {
		if(!running || canvas.isDisposed())
			return;
		phase = p;
		phaseStarted = System.currentTimeMillis();
		update();
	}

	void stop() {
		running = false;
		if(canvas.isDisposed())
			return;
		canvas.getDisplay().timerExec(-1, tick); // cancel the pending frame
		canvas.setVisible(false);
		label.setText("");
	}

	String getPhase() {
		return phase;
	}

	boolean isRunning() {
		return running;
	}

	private void tick() {
		if(!running || canvas.isDisposed())
			return;
		angle = (angle + 30) % 360;
		canvas.redraw();
		update();
		canvas.getDisplay().timerExec(FRAME_MS, tick);
	}

	private void update() {
		if(label.isDisposed())
			return;
		long now = System.currentTimeMillis();
		long phaseSec = (now - phaseStarted) / 1000, totalSec = (now - started) / 1000;
		String text = phase + " " + format(phaseSec) + (totalSec > phaseSec + 1 ? "  (total " + format(totalSec) + ")" : "");
		if(!text.equals(label.getText())) {
			label.setText(text);
			label.getParent().layout(new org.eclipse.swt.widgets.Control[]{label});
		}
	}

	private static String format(long s) {
		return s < 60 ? s + " s" : s / 60 + " min " + s % 60 + " s";
	}

	/** A 270 degree arc with a fading tail, rotating. */
	private void paint(GC gc) {
		Point size = canvas.getSize();
		int d = Math.min(size.x, size.y) - 3;
		if(d <= 2)
			return;
		gc.setAntialias(SWT.ON);
		gc.setLineCap(SWT.CAP_ROUND);
		gc.setLineWidth(Math.max(2, d / 7));
		Color fg = canvas.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
		Color bg = canvas.getBackground();
		int x = (size.x - d) / 2, y = (size.y - d) / 2;
		// faint full circle as track
		Color track = blend(fg, bg, 0.82);
		gc.setForeground(track);
		gc.drawOval(x, y, d, d);
		track.dispose();
		// the arc in segments from faint (tail) to strong (head)
		int segments = 9, span = 270;
		for(int i = 0; i < segments; i++) {
			double t = (i + 1) / (double)segments;
			Color c = blend(fg, bg, 1 - t);
			gc.setForeground(c);
			// SWT angles run counter-clockwise; the angle grows, so the bright head (last segment) leads
			int start = angle + i * span / segments;
			gc.drawArc(x, y, d, d, start, span / segments + 2);
			c.dispose();
		}
	}

	private static Color blend(Color a, Color b, double t) {
		return new Color(a.getDevice(), (int)(a.getRed() + (b.getRed() - a.getRed()) * t), (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t), (int)(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}
}
