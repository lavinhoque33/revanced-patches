/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug only: finds the protobufs and byte arrays that the Litho view of a row can reach, to see whether its row
 * model survives in the view (which fields hold it, how deep) and what it contains. Looks from the view's component
 * tree, breadth first, so the shallowest are listed first, within a limit of depth and of objects visited.
 * Fields are read by reflection in the app's obfuscated classes, so the paths are names of that version.
 */
final class LithoModelSearch {
    /** A protobuf or byte array reachable from a view. */
    record Found(String path, int depth, String type, byte[] bytes) {}

    private static final String COMPONENT_TREE = "com.facebook.litho.ComponentTree";
    private static final String[] FRAMEWORK_PREFIXES = {"android.", "java.", "javax.", "dalvik.", "kotlin.", "sun."};
    private static final int MAX_DEPTH = 8;
    private static final int MAX_OBJECTS = 6000;
    private static final int MAX_ELEMENTS = 64;
    private static final int MIN_BYTES = 64;

    private LithoModelSearch() {}

    /**
     * The protobufs and byte arrays reachable from the component tree of the row's Litho view (the row or its nearest
     * ancestor that has one), shallowest first; empty when there is no tree.
     */
    static List<Found> find(View row) {
        List<Found> found = new ArrayList<>();
        Object tree = null;
        View view = row;
        while (view != null && tree == null) {
            tree = treeOf(view);
            view = view.getParent() instanceof View parent ? parent : null;
        }
        if (tree == null) return found;

        Map<Object, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(tree, "tree", 0));
        while (!pending.isEmpty() && visited.size() < MAX_OBJECTS) {
            Node node = pending.poll();
            if (visited.put(node.value, Boolean.TRUE) != null) continue;

            if (node.value instanceof MessageLite message) {
                found.add(new Found(node.path, node.depth, message.getClass().getName(), message.toByteArray()));
            } else if (node.value instanceof byte[] bytes) {
                if (bytes.length >= MIN_BYTES) found.add(new Found(node.path, node.depth, "byte[]", bytes));
            } else if (node.depth < MAX_DEPTH) {
                expand(node, pending);
            }
        }
        return found;
    }

    private record Node(Object value, String path, int depth) {}

    @Nullable
    private static Object treeOf(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(COMPONENT_TREE)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object tree = field.get(view);
                    if (tree != null) return tree;
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Not readable: try the next view up.
                }
            }
        }
        return null;
    }

    private static void expand(Node node, ArrayDeque<Node> pending) {
        Object value = node.value;
        if (value instanceof Object[] array) {
            for (int i = 0; i < Math.min(array.length, MAX_ELEMENTS); i++) add(pending, array[i], node, "[" + i + "]");
        } else if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                if (i >= MAX_ELEMENTS) break;
                add(pending, element, node, "[" + i++ + "]");
            }
        } else if (value instanceof Map<?, ?> map) {
            int i = 0;
            for (Object element : map.values()) {
                if (i >= MAX_ELEMENTS) break;
                add(pending, element, node, "{" + i++ + "}");
            }
        } else if (isAppObject(value)) {
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;

                    try {
                        field.setAccessible(true);
                        add(pending, field.get(value), node, "." + field.getName());
                    } catch (IllegalAccessException | RuntimeException ignored) {
                        // A field that cannot be read leads nowhere.
                    }
                }
            }
        }
    }

    private static void add(ArrayDeque<Node> pending, @Nullable Object value, Node from, String step) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return;

        pending.add(new Node(value, from.path + step, from.depth + 1));
    }

    /** Whether an object is one of the app's own, whose fields are worth following. */
    private static boolean isAppObject(Object value) {
        if (value instanceof Context || value instanceof View || value instanceof Drawable
                || value instanceof Bitmap || value instanceof Handler || value instanceof Class
                || value instanceof ClassLoader || value instanceof Thread) {
            return false;
        }
        String name = value.getClass().getName();
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (name.startsWith(prefix)) return false;
        }
        return true;
    }
}
