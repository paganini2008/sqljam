/*
 * Copyright 2023-2026 Fred Feng
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
 */
package com.github.sqljam.face.view;

import java.awt.Polygon;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: VideoRecorder records the windows of the application into a video without a screen recorder: frames
 *               are rendered by JavaFX (the main window, its dialogs and popups at their positions), a pointer and
 *               click rings are drawn on top, and the frames are piped to ffmpeg. Subtitles are collected with their
 *               times and burned in together with the background music when the recording is finished.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
final class VideoRecorder {

    private static final long CLICK_MILLIS = 450;
    /**
     * Pointer of the video, in logical pixels
     */
    private static final Polygon POINTER = new Polygon(new int[]{0, 0, 4, 7, 10, 7, 12}, new int[]{0, 17, 13, 20,
            19, 12, 12}, 7);

    private final Stage stage;
    private final String ffmpeg;
    private final double scale;
    private final int fps;
    private final int width;
    private final int height;
    /**
     * Height of the application in the video, the subtitles have their own band below it
     */
    private final int sceneHeight;
    private final List<Caption> captions = new ArrayList<>();
    private final AtomicBoolean rendering = new AtomicBoolean();
    private final boolean[] pointerFill;
    private final boolean[] pointerEdge;
    private final int pointerWidth;
    private final int pointerHeight;

    private volatile double pointerX;
    private volatile double pointerY;
    private volatile long clickNanos;
    private volatile byte[] frame;
    private volatile boolean running;
    private long startNanos;
    private long frameCount;
    private Process process;
    private Thread writer;
    private ScheduledExecutorService renderer;
    private File rawVideo;

    /**
     * @param stage the main window, the video has the size of its scene
     * @param scale pixels of the video per logical pixel, e.g. 1.25 for sharper text
     * @param captionBand height of the band of the subtitles below the application in logical pixels, the
     *                    subtitles never cover the user interface
     */
    VideoRecorder(Stage stage, String ffmpeg, double scale, int fps, int captionBand) {
        this.stage = stage;
        this.ffmpeg = ffmpeg;
        this.scale = scale;
        this.fps = fps;
        // Even sizes for yuv420p
        this.width = (int) Math.round(stage.getScene().getWidth() * scale) / 2 * 2;
        this.sceneHeight = (int) Math.round(stage.getScene().getHeight() * scale);
        this.height = (int) Math.round((stage.getScene().getHeight() + captionBand) * scale) / 2 * 2;
        this.pointerX = stage.getScene().getWidth() / 2;
        this.pointerY = stage.getScene().getHeight() / 2;
        pointerWidth = (int) Math.ceil(14 * scale);
        pointerHeight = (int) Math.ceil(22 * scale);
        pointerFill = new boolean[pointerWidth * pointerHeight];
        pointerEdge = new boolean[pointerWidth * pointerHeight];
        for (int y = 0; y < pointerHeight; y++) {
            for (int x = 0; x < pointerWidth; x++) {
                pointerFill[y * pointerWidth + x] = POINTER.contains((x + 0.5) / scale, (y + 0.5) / scale);
            }
        }
        // The edge is the outside next to the inside
        for (int y = 0; y < pointerHeight; y++) {
            for (int x = 0; x < pointerWidth; x++) {
                boolean near = false;
                for (int dy = -1; dy <= 1 && !near; dy++) {
                    for (int dx = -1; dx <= 1 && !near; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        near = nx >= 0 && ny >= 0 && nx < pointerWidth && ny < pointerHeight
                                && pointerFill[ny * pointerWidth + nx];
                    }
                }
                pointerEdge[y * pointerWidth + x] = near && !pointerFill[y * pointerWidth + x];
            }
        }
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    /**
     * Starts rendering and encoding into the raw video, a fast encoding which is encoded again by finish
     */
    void start(File rawVideo) throws IOException, InterruptedException {
        this.rawVideo = rawVideo;
        process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
                "-pix_fmt", "bgra", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-", "-c:v",
                "libx264", "-preset", "ultrafast", "-crf", "12", "-pix_fmt", "yuv420p", rawVideo.getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(new File(rawVideo.getPath() + ".log")).start();
        running = true;
        long period = 1_000_000_000L / fps;
        renderer = Executors.newSingleThreadScheduledExecutor();
        renderer.scheduleAtFixedRate(() -> {
            if (running && rendering.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    try {
                        frame = render();
                    } finally {
                        rendering.set(false);
                    }
                });
            }
        }, 0, period, TimeUnit.NANOSECONDS);
        while (frame == null) {
            Thread.sleep(10);
        }
        startNanos = System.nanoTime();
        writer = new Thread(() -> write(period), "video-writer");
        writer.start();
    }

    /**
     * Writes the latest frame at the frame rate of the video, a frame is repeated when rendering is slower, so that
     * the video keeps the time of the recording and the subtitles
     */
    private void write(long period) {
        try (OutputStream out = process.getOutputStream()) {
            while (running) {
                long due = startNanos + frameCount * period;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                out.write(frame);
                frameCount++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write the video", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Shows the subtitle from now until the next one
     */
    void caption(String text) {
        long now = elapsedMillis();
        endCaption();
        captions.add(new Caption(now, text));
    }

    void endCaption() {
        if (!captions.isEmpty() && captions.get(captions.size() - 1).end < 0) {
            captions.get(captions.size() - 1).end = elapsedMillis();
        }
    }

    /**
     * Moves the pointer to the position of the main scene with an eased motion
     */
    void movePointer(double x, double y, long millis) throws InterruptedException {
        double fromX = pointerX;
        double fromY = pointerY;
        long steps = Math.max(1, millis / 15);
        for (long i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double eased = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            pointerX = fromX + (x - fromX) * eased;
            pointerY = fromY + (y - fromY) * eased;
            Thread.sleep(15);
        }
    }

    void click() {
        clickNanos = System.nanoTime();
    }

    /**
     * Stops recording, the raw video is complete afterwards
     */
    void stop() throws InterruptedException, IOException {
        endCaption();
        running = false;
        renderer.shutdownNow();
        writer.join();
        if (process.waitFor() != 0) {
            throw new IOException("ffmpeg failed, see " + rawVideo.getPath() + ".log");
        }
    }

    double getDurationSeconds() {
        return (double) frameCount / fps;
    }

    /**
     * Encodes the final video: subtitles burned in, the music faded in and out under the whole video
     */
    void finish(File output, File music, String font) throws IOException, InterruptedException {
        File directory = rawVideo.getParentFile();
        File subtitles = new File(directory, "subtitles.ass");
        Files.writeString(subtitles.toPath(), toAss(font), StandardCharsets.UTF_8);
        double duration = getDurationSeconds();
        String audio = String.format(Locale.ENGLISH, "[1:a]volume=0.5,afade=t=in:d=2,afade=t=out:st=%.2f:d=3[a]",
                Math.max(0, duration - 3));
        output.getAbsoluteFile().getParentFile().mkdirs();
        Process encoder = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i",
                rawVideo.getName(), "-i", music.getAbsolutePath(), "-filter_complex", "[0:v]subtitles=" + subtitles
                .getName() + "[v];" + audio, "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "slow",
                "-crf", "22", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-t", String.format(
                Locale.ENGLISH, "%.2f", duration), "-movflags", "+faststart", output.getAbsolutePath())
                .directory(directory).redirectErrorStream(true).redirectOutput(new File(directory, "finish.log"))
                .start();
        if (encoder.waitFor() != 0) {
            throw new IOException("ffmpeg failed, see " + new File(directory, "finish.log"));
        }
    }

    private String toAss(String font) {
        int band = height - sceneHeight;
        int size = Math.round(band * 0.34f);
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(width).append("\nPlayResY: ")
                .append(height).append("\nWrapStyle: 0\n\n[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, ")
                .append("BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, ")
                .append("BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,").append(font).append(',').append(size)
                .append(",&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,60,60,")
                .append(Math.round((band - size * 1.2f) / 2))
                .append(",1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, ")
                .append("Effect, Text\n");
        for (Caption caption : captions) {
            long end = caption.end < 0 ? Math.round(getDurationSeconds() * 1000) : caption.end;
            ass.append("Dialogue: 0,").append(assTime(caption.start)).append(',').append(assTime(end))
                    .append(",Default,,0,0,0,,").append(caption.text).append('\n');
        }
        return ass.toString();
    }

    private static String assTime(long millis) {
        long centis = millis / 10;
        return String.format(Locale.ENGLISH, "%d:%02d:%02d.%02d", centis / 360000, centis / 6000 % 60,
                centis / 100 % 60, centis % 100);
    }

    /**
     * Renders the windows of the application at their positions relative to the main scene, then the pointer
     */
    private byte[] render() {
        byte[] canvas = new byte[width * height * 4];
        for (int i = 0; i < canvas.length; i += 4) {
            // The band of the subtitles: #0b0e14
            canvas[i] = 0x14;
            canvas[i + 1] = 0x0e;
            canvas[i + 2] = 0x0b;
            canvas[i + 3] = (byte) 255;
        }
        Scene main = stage.getScene();
        double originX = stage.getX() + main.getX();
        double originY = stage.getY() + main.getY();
        for (Window window : new ArrayList<>(Window.getWindows())) {
            Scene scene = window.getScene();
            if (!window.isShowing() || scene == null || scene.getRoot() == null) {
                continue;
            }
            int windowWidth = (int) Math.ceil(scene.getWidth() * scale);
            int windowHeight = (int) Math.ceil(scene.getHeight() * scale);
            if (windowWidth <= 0 || windowHeight <= 0) {
                continue;
            }
            int x = (int) Math.round((window.getX() + scene.getX() - originX) * scale);
            int y = (int) Math.round((window.getY() + scene.getY() - originY) * scale);
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(scale, scale));
            parameters.setViewport(new Rectangle2D(0, 0, windowWidth, windowHeight));
            boolean popup = window instanceof PopupWindow;
            parameters.setFill(popup || !(scene.getFill() instanceof Color) ? Color.TRANSPARENT : scene.getFill());
            WritableImage image = scene.getRoot().snapshot(parameters, null);
            if (window != stage && !popup) {
                drawShadow(canvas, x, y, windowWidth, windowHeight);
            }
            blend(canvas, image, x, y);
        }
        drawClick(canvas);
        drawPointer(canvas);
        return canvas;
    }

    private void blend(byte[] canvas, WritableImage image, int left, int top) {
        int imageWidth = (int) image.getWidth();
        int imageHeight = (int) image.getHeight();
        byte[] pixels = new byte[imageWidth * imageHeight * 4];
        image.getPixelReader().getPixels(0, 0, imageWidth, imageHeight, PixelFormat.getByteBgraPreInstance(), pixels,
                0, imageWidth * 4);
        for (int y = 0; y < imageHeight; y++) {
            int canvasY = top + y;
            if (canvasY < 0 || canvasY >= sceneHeight) {
                continue;
            }
            for (int x = 0; x < imageWidth; x++) {
                int canvasX = left + x;
                if (canvasX < 0 || canvasX >= width) {
                    continue;
                }
                int source = (y * imageWidth + x) * 4;
                int target = (canvasY * width + canvasX) * 4;
                int alpha = pixels[source + 3] & 0xff;
                if (alpha == 255) {
                    canvas[target] = pixels[source];
                    canvas[target + 1] = pixels[source + 1];
                    canvas[target + 2] = pixels[source + 2];
                } else if (alpha > 0) {
                    int rest = 255 - alpha;
                    for (int c = 0; c < 3; c++) {
                        canvas[target + c] = (byte) ((pixels[source + c] & 0xff) + (canvas[target + c] & 0xff)
                                * rest / 255);
                    }
                }
            }
        }
    }

    /**
     * Dialogs have no window frame in the video, a shadow and a border separate them from the main window
     */
    private void drawShadow(byte[] canvas, int left, int top, int w, int h) {
        int spread = (int) Math.round(14 * scale);
        for (int y = top - spread; y < top + h + spread; y++) {
            for (int x = left - spread; x < left + w + spread; x++) {
                if (x < 0 || y < 0 || x >= width || y >= sceneHeight) {
                    continue;
                }
                int dx = Math.max(Math.max(left - x, x - (left + w - 1)), 0);
                int dy = Math.max(Math.max(top - y, y - (top + h - 1)), 0);
                double distance = Math.sqrt(dx * dx + dy * dy);
                if (distance == 0) {
                    continue;
                }
                double darken = distance <= 1.5 * scale ? 0.35 : 0.45 * Math.max(0, 1 - distance / spread);
                darken(canvas, (y * width + x) * 4, darken);
            }
        }
    }

    private static void darken(byte[] canvas, int index, double amount) {
        for (int c = 0; c < 3; c++) {
            canvas[index + c] = (byte) ((canvas[index + c] & 0xff) * (1 - amount));
        }
    }

    private void drawClick(byte[] canvas) {
        long age = (System.nanoTime() - clickNanos) / 1_000_000;
        if (clickNanos == 0 || age > CLICK_MILLIS) {
            return;
        }
        double t = (double) age / CLICK_MILLIS;
        double radius = (6 + 18 * t) * scale;
        double thickness = 3 * scale;
        double opacity = 0.85 * (1 - t);
        int centerX = (int) Math.round(pointerX * scale);
        int centerY = (int) Math.round(pointerY * scale);
        int reach = (int) Math.ceil(radius + thickness);
        for (int y = centerY - reach; y <= centerY + reach; y++) {
            for (int x = centerX - reach; x <= centerX + reach; x++) {
                if (x < 0 || y < 0 || x >= width || y >= sceneHeight) {
                    continue;
                }
                double distance = Math.hypot(x - centerX, y - centerY);
                if (Math.abs(distance - radius) <= thickness / 2) {
                    int index = (y * width + x) * 4;
                    // Accent blue #4493f8 in BGRA
                    mix(canvas, index, 0xf8, 0x93, 0x44, opacity);
                }
            }
        }
    }

    private static void mix(byte[] canvas, int index, int blue, int green, int red, double opacity) {
        canvas[index] = (byte) ((canvas[index] & 0xff) * (1 - opacity) + blue * opacity);
        canvas[index + 1] = (byte) ((canvas[index + 1] & 0xff) * (1 - opacity) + green * opacity);
        canvas[index + 2] = (byte) ((canvas[index + 2] & 0xff) * (1 - opacity) + red * opacity);
    }

    private void drawPointer(byte[] canvas) {
        int left = (int) Math.round(pointerX * scale);
        int top = (int) Math.round(pointerY * scale);
        for (int y = 0; y < pointerHeight; y++) {
            for (int x = 0; x < pointerWidth; x++) {
                int canvasX = left + x;
                int canvasY = top + y;
                if (canvasX < 0 || canvasY < 0 || canvasX >= width || canvasY >= sceneHeight) {
                    continue;
                }
                int index = (canvasY * width + canvasX) * 4;
                if (pointerFill[y * pointerWidth + x]) {
                    mix(canvas, index, 255, 255, 255, 1);
                } else if (pointerEdge[y * pointerWidth + x]) {
                    mix(canvas, index, 0, 0, 0, 1);
                }
            }
        }
    }

    private static final class Caption {

        final long start;
        final String text;
        long end = -1;

        Caption(long start, String text) {
            this.start = start;
            this.text = text;
        }
    }
}
