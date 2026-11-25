/*
 * Copyright 2026 Morphe.
 * Copyright (C) 2026 lavinhoque33
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-25.
 */

package app.morphe.extension.shared.patches.glassmenus;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.shared.patches.AppCheckPatch;
import app.morphe.extension.shared.ui.glass.GlassBackdrop;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Renders a bounded backdrop inside a panel that cannot use Android's whole-window blur. On Android
 * 14+ ordinary view hierarchies can be recorded on the menu's first traversal. Separate windows
 * can also sample the activity buffer with PixelCopy, which excludes pixels in a SurfaceView layer.
 */
@RequiresApi(Build.VERSION_CODES.S)
final class BlurRegion {
    private static final int MAX_COPY_EDGE = 768;
    private static final int MAX_LOCAL_EDGE = 4096;

    private final View target;
    private final View foreground;
    private final Window source;
    private final View sourceDecor;
    private final boolean inWindow;
    private final int backgroundColor;
    private final int[] targetLocation = new int[2];
    private final int[] sourceLocation = new int[2];
    private final Rect requestedPanel = new Rect();
    private final Rect attemptedPanel = new Rect();
    private final Rect copiedArea = new Rect();
    private final Rect localPanel = new Rect();
    private final Rect testBounds = new Rect();
    private final RectF recordingArea = new RectF();
    private final List<View> siblings = new ArrayList<>();

    private boolean useWindowSnapshot = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    private int radius;
    private int generation;
    private boolean pending;
    private int renderVersion;
    private int offsetX;
    private int offsetY;
    @Nullable private RenderNode recorded;
    @Nullable private GlassBackdrop localBackdrop;

    @Nullable
    static Window sourceWindow(View target, @Nullable Activity owner) {
        Activity activity = owner != null ? owner : contextActivity(target.getContext());
        if (activity == null) {
            activity = Utils.getActivity();
            View decor = activity == null ? null : activity.getWindow().peekDecorView();
            if (decor == null || !Objects.equals(decor.getApplicationWindowToken(),
                    target.getApplicationWindowToken())) return null;
        }

        return activity.getWindow();
    }

    static boolean canSample(Window window) {
        View decor = window.peekDecorView();
        return decor != null && isEligibleSource(window, decor)
                && decor.getWindowVisibility() == View.VISIBLE && decor.isShown();
    }

    private static boolean isEligibleSource(Window window, View decor) {
        return decor.isAttachedToWindow() && decor.isHardwareAccelerated()
                && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
    }

    @Nullable
    static BlurRegion create(View target, View foreground, @Nullable Activity owner, int color) {
        Window window = sourceWindow(target, owner);
        if (window == null) return null;

        View decor = window.peekDecorView();
        if (decor == null || !isEligibleSource(window, decor) || decor.getDisplay() == null
                || target.getDisplay() == null
                || decor.getDisplay().getDisplayId() != target.getDisplay().getDisplayId()) return null;

        if (decor == target.getRootView() && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Do not rerender live view trees through RenderEffect on Android 12 or 13.
            return null;
        }

        return new BlurRegion(target, foreground, window, decor, color);
    }

    private BlurRegion(View target, View foreground, Window source, View sourceDecor, int backgroundColor) {
        this.target = target;
        this.foreground = foreground;
        this.source = source;
        this.sourceDecor = sourceDecor;
        this.backgroundColor = backgroundColor;
        inWindow = sourceDecor == target.getRootView();
    }

    @Nullable
    static Activity contextActivity(Context context) {
        for (int depth = 0; depth < 8 && context instanceof ContextWrapper wrapper; depth++) {
            if (context instanceof Activity activity) return activity;
            context = wrapper.getBaseContext();
        }
        return null;
    }

    /** False means no real backdrop is ready yet, so the caller must keep every stock fill. */
    boolean prepare(Rect panel, int blurRadius, boolean layoutChanged) {
        if (!canSample(source)) {
            if (recorded != null || pending) clear();
            return false;
        }

        if (radius != blurRadius) {
            clear();
            radius = blurRadius;
        }
        if (radius == 0) return true; // Deliberate zero, never used as an unavailable-blur fallback.

        if (inWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            target.getLocationOnScreen(targetLocation);
            if (layoutChanged || !localPanel.equals(panel)
                    || offsetX != targetLocation[0] || offsetY != targetLocation[1]
                    || !hasBackdrop()) recordLocal(panel);
            return hasBackdrop();
        }

        target.getLocationOnScreen(targetLocation);
        sourceDecor.getLocationOnScreen(sourceLocation);
        int relativeX = sourceLocation[0] - targetLocation[0];
        int relativeY = sourceLocation[1] - targetLocation[1];
        testBounds.set(panel);
        testBounds.offset(-relativeX, -relativeY);
        // A translating or over-tall sheet can extend past the activity surface. Its visible part
        // still has a real backdrop, even though the entire logical panel cannot be copied.
        if (!clipToSource(testBounds, sourceDecor.getWidth(), sourceDecor.getHeight())) {
            requestedPanel.setEmpty();
            attemptedPanel.setEmpty();
            return false;
        }
        boolean visiblePanelChanged = !requestedPanel.equals(testBounds);
        if (visiblePanelChanged) requestedPanel.set(testBounds);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !useWindowSnapshot
                && (!hasBackdrop() || !copiedArea.contains(requestedPanel))) {
            useWindowSnapshot = requiresWindowSnapshot(sourceDecor, AppCheckPatch.IS_YOUTUBE);
            if (useWindowSnapshot) {
                recorded = null;
            } else {
                recordWindow();
            }
        }
        if (hasBackdrop() && copiedArea.contains(requestedPanel)) {
            int left = relativeX + copiedArea.left;
            int top = relativeY + copiedArea.top;
            if (visiblePanelChanged || offsetX != left || offsetY != top) {
                offsetX = left;
                offsetY = top;
                renderVersion++;
            }
            return true;
        }

        if (useWindowSnapshot && !pending) {
            if (recorded != null && !hasBackdrop()) {
                recorded = null;
                attemptedPanel.setEmpty();
            }
            if (!attemptedPanel.equals(requestedPanel)) requestCopy();
        }
        return false;
    }

    boolean hasFailedCopy() {
        return useWindowSnapshot && !pending
                && !requestedPanel.isEmpty() && attemptedPanel.equals(requestedPanel)
                && (!hasBackdrop() || !copiedArea.contains(requestedPanel));
    }

    static boolean requiresWindowSnapshot(View decor, boolean youtube) {
        // A hardware replay can reposition the live SurfaceView through its RenderNode position
        // listener. Clipping the backdrop doesn't prevent HWUI from visiting that node.
        return youtube && containsSurfaceView(decor);
    }

    private static boolean containsSurfaceView(View view) {
        if (view instanceof SurfaceView) return true;
        if (!(view instanceof ViewGroup group)) return false;

        for (int index = 0, count = group.getChildCount(); index < count; index++) {
            if (containsSurfaceView(group.getChildAt(index))) return true;
        }
        return false;
    }

    private boolean hasBackdrop() {
        return recorded != null && recorded.hasDisplayList();
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void recordWindow() {
        if (!copyArea(requestedPanel, sourceDecor.getWidth(), sourceDecor.getHeight(), radius * 2, copiedArea)) {
            return;
        }

        float scale = copyScale(copiedArea);
        int width = Math.max(1, Math.round(copiedArea.width() * scale));
        int height = Math.max(1, Math.round(copiedArea.height() * scale));
        RenderNode node = newSampleNode("Glass menu window backdrop", copiedArea, width, height);
        RecordingCanvas canvas = node.beginRecording();
        try {
            canvas.scale((float) width / copiedArea.width(), (float) height / copiedArea.height());
            canvas.translate(-copiedArea.left, -copiedArea.top);
            sourceDecor.draw(canvas);
            // A SurfaceView can punch a transparent hole in the owner. Do not pass through a sharp
            // external video layer in that hole; composite only the owner's UI over its base color.
            canvas.drawColor(backgroundColor, PorterDuff.Mode.DST_OVER);
        } finally {
            node.endRecording();
        }
        recorded = node;
        renderVersion++;
    }

    private static float copyScale(Rect sample) {
        return Math.min(0.5f, (float) MAX_COPY_EDGE / Math.max(sample.width(), sample.height()));
    }

    private void requestCopy() {
        attemptedPanel.set(requestedPanel);
        Rect sample = new Rect();
        if (!copyArea(requestedPanel, sourceDecor.getWidth(), sourceDecor.getHeight(), radius * 2, sample)) return;

        float scale = copyScale(sample);
        int width = Math.max(1, Math.round(sample.width() * scale));
        int height = Math.max(1, Math.round(sample.height() * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int requestGeneration = generation;
        pending = true;
        try {
            PixelCopy.request(source, sample, bitmap, result -> {
                if (generation != requestGeneration) {
                    bitmap.recycle();
                    return;
                }

                pending = false;
                if (result == PixelCopy.SUCCESS && canSample(source)
                        && !requestedPanel.isEmpty() && sample.contains(requestedPanel)) {
                    try {
                        recordCopy(bitmap, sample);
                    } catch (RuntimeException ex) {
                        bitmap.recycle();
                        Logger.printException(() -> "Could not render the glass menu snapshot", ex);
                    }
                } else {
                    bitmap.recycle();
                    if (result != PixelCopy.SUCCESS) {
                        Logger.printDebug(() -> "Glass menu backdrop unavailable: PixelCopy result " + result);
                    }
                }
                target.postInvalidateOnAnimation();
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException ex) {
            pending = false;
            bitmap.recycle();
            Logger.printDebug(() -> "Glass menu source window is not ready for PixelCopy", ex);
        }
    }

    /** Clips the panel in the owning activity's coordinate space; an empty intersection stays stock. */
    static boolean clipToSource(Rect panel, int sourceWidth, int sourceHeight) {
        return !panel.isEmpty() && panel.intersect(0, 0, sourceWidth, sourceHeight);
    }

    /** The blur may sample beyond the visible panel, but never outside the activity surface. */
    static boolean copyArea(Rect panel, int sourceWidth, int sourceHeight, int padding, Rect result) {
        result.set(panel);
        if (!clipToSource(result, sourceWidth, sourceHeight)) {
            result.setEmpty();
            return false;
        }

        result.set(Math.max(0, result.left - padding), Math.max(0, result.top - padding),
                Math.min(sourceWidth, result.right + padding), Math.min(sourceHeight, result.bottom + padding));
        return true;
    }

    private RenderNode newSampleNode(String name, Rect sample, int width, int height) {
        // Downsample the RenderEffect and its working surface as well as the source.
        RenderNode node = new RenderNode(name);
        node.setPosition(0, 0, width, height);
        node.setRenderEffect(RenderEffect.createBlurEffect(
                (float) radius * width / sample.width(), (float) radius * height / sample.height(),
                Shader.TileMode.CLAMP));
        return node;
    }

    private void recordCopy(Bitmap bitmap, Rect sample) {
        RenderNode node = newSampleNode("Glass menu snapshot", sample, bitmap.getWidth(), bitmap.getHeight());
        RecordingCanvas canvas = node.beginRecording();
        try {
            canvas.drawColor(backgroundColor);
            canvas.drawBitmap(bitmap, 0, 0, null);
        } finally {
            node.endRecording();
        }
        recorded = node;
        copiedArea.set(sample);
        renderVersion++;
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void recordLocal(Rect panel) {
        localPanel.set(panel);
        offsetX = targetLocation[0];
        offsetY = targetLocation[1];
        if (panel.width() > MAX_LOCAL_EDGE || panel.height() > MAX_LOCAL_EDGE) {
            recorded = null;
            return;
        }

        // The playlist overlay's own list and close bar are both in foreground; never record either one.
        siblings.clear();
        testBounds.set(panel);
        testBounds.offset(targetLocation[0], targetLocation[1]);
        collectBehind(foreground, testBounds, 0);
        if (siblings.isEmpty()) {
            recorded = null;
            return;
        }

        RenderNode node = recorded != null ? recorded : new RenderNode("Glass menu in-window backdrop");
        if (localBackdrop == null) localBackdrop = new GlassBackdrop("Glass menu panel", radius);
        node.setPosition(panel.left, panel.top, panel.right, panel.bottom);
        RecordingCanvas canvas = node.beginRecording();
        try {
            canvas.translate(-panel.left, -panel.top);
            recordingArea.set(panel);
            recordingArea.inset(-radius, -radius);
            localBackdrop.draw(canvas, target, siblings, recordingArea, backgroundColor);
        } finally {
            node.endRecording();
            siblings.clear();
        }
        recorded = node;
        renderVersion++;
    }

    private void collectBehind(View branch, Rect panelOnScreen, int depth) {
        if (depth >= 24 || !(branch.getParent() instanceof ViewGroup parent)) return;

        collectBehind(parent, panelOnScreen, depth + 1);
        for (int index = 0, count = parent.getChildCount(); index < count; index++) {
            View sibling = parent.getChildAt(index);
            if (sibling == branch) break;
            if (!sibling.isShown() || sibling.getAlpha() != 1f || sibling.getZ() > branch.getZ()) continue;

            sibling.getLocationOnScreen(sourceLocation);
            int left = sourceLocation[0];
            int top = sourceLocation[1];
            if (panelOnScreen.intersects(left, top, left + sibling.getWidth(), top + sibling.getHeight())) {
                siblings.add(sibling);
            }
        }
    }

    void draw(Canvas canvas) {
        RenderNode node = recorded;
        if (node == null || !node.hasDisplayList()) return;
        if (inWindow) {
            canvas.drawRenderNode(node);
        } else {
            int save = canvas.save();
            int relativeX = offsetX - copiedArea.left;
            int relativeY = offsetY - copiedArea.top;
            canvas.clipRect(requestedPanel.left + relativeX, requestedPanel.top + relativeY,
                    requestedPanel.right + relativeX, requestedPanel.bottom + relativeY);
            canvas.translate(offsetX, offsetY);
            canvas.scale((float) copiedArea.width() / node.getWidth(), (float) copiedArea.height() / node.getHeight());
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);
        }
    }

    int renderVersion() {
        return renderVersion;
    }

    void clear() {
        generation++;
        renderVersion++;
        pending = false;
        requestedPanel.setEmpty();
        attemptedPanel.setEmpty();
        copiedArea.setEmpty();
        localPanel.setEmpty();
        localBackdrop = null;
        recorded = null;
    }
}
