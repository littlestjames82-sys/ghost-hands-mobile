package com.ghosthands.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.25 (accessibility eyes): the pure converter from an
 * accessibility node tree (A11yNode, copied out of the
 * platform by GhostA11yService) to the browser map's own
 * shape — a list of ElementInfo in the numbered discipline
 * Eyes.Perception renders — so a page the injected DOM eyes
 * cannot see can still be PERCEIVED through the node tree.
 *
 * SCOPE, kept narrow on purpose:
 *  - Only the subtree of the page's own WebView is converted
 *    (the first VISIBLE node of class android.webkit.WebView
 *    in walk order). The rest of the tree is this app's own
 *    chrome — the goal field, the RUN button, the feed — and
 *    it is not page evidence; the agent must never be handed
 *    its own controls as if they were the page's. A tree
 *    with no visible WebView converts to null.
 *  - Only INTERACTIVE nodes become elements — clickable,
 *    editable or checkable, and visible — the contract the
 *    DOM map keeps (its list is interactive elements only).
 *    Non-interactive nodes still feed the text preview.
 *  - Every element is stamped source "a11y" and keeps
 *    domIndex -1: it has no slot in the JS stash, and the
 *    agent loop refuses element actions against it (the
 *    eyes are READ-ONLY; see the loop's eyes guard in
 *    Agent). This class performs no action of any kind: it
 *    holds no reference to the service, dispatches no
 *    gesture and calls no node action — it could not act
 *    if it wanted to.
 *  - The redaction discipline is NodeSketch's, shared, not
 *    re-invented: a node's label comes from
 *    NodeSketch.labelOf, and a withheld node (password, or
 *    any node under a secure ancestor) contributes no text
 *    at all. The withheld rule judges the FLAG, so even a
 *    tree whose converter misbehaved (a password node still
 *    carrying text) converts clean.
 *
 * The walks are iterative and bounded (MAX_VISITED across
 * both), pre-order like the platform's child order, so
 * element numbers are walk order — the map discipline the
 * planner already knows. Pure: no Android.
 * GhostA11yService and MainActivity supply the tree and the
 * page's URL/title; the loop supplies the judgement.
 */
public final class A11yMap {

    private A11yMap() { }

    /** Elements kept at most — the browser map's standing
     *  element cap (Eyes' shaped maps hold 40). */
    public static final int MAX_ELEMENTS = 40;
    /** Nodes examined across both walks, at most (the
     *  sketch's walk bound). */
    public static final int MAX_VISITED = 4096;
    /** The preview's character cap (the DOM preview's ~240). */
    public static final int TEXT_PREVIEW_CHARS = 240;
    /** The class the page's own view declares. */
    public static final String WEBVIEW_CLASS =
            "android.webkit.WebView";
    /** The source stamp every converted element carries. */
    public static final String SOURCE = "a11y";

    /** One conversion of one WebView subtree. */
    public static class Result {
        public final List<ElementInfo> elements;
        /** The page's text as the tree declares it (labels in
         *  walk order), capped — the Perception preview. */
        public final String textPreview;
        /** Nodes examined inside the WebView subtree. */
        public final int visited;
        /** Interactive nodes the element cap left out. */
        public final int omitted;

        Result(List<ElementInfo> elements, String textPreview,
               int visited, int omitted) {
            this.elements = elements;
            this.textPreview = textPreview;
            this.visited = visited;
            this.omitted = omitted;
        }
    }

    /** One walk frame: the node, and whether a secure
     *  ancestor already withholds its text. */
    private static class Frame {
        final A11yNode node;
        final boolean secure;

        Frame(A11yNode node, boolean secure) {
            this.node = node;
            this.secure = secure;
        }
    }

    /** Convert a captured tree, or return null when the tree
     *  holds no visible WebView (the page is not in this
     *  window, or this window is not ours to read). */
    public static Result convert(A11yNode root) {
        if (root == null) return null;
        int budget = MAX_VISITED;
        // Phase 1: find the page — the first visible WebView
        // in walk order, carrying the secure flag it inherits
        // (inactive tabs' WebViews are GONE in the hierarchy
        // and report not-visible, so the visible one is the
        // active tab's).
        A11yNode webView = null;
        boolean webSecure = false;
        ArrayDeque<Frame> stack = new ArrayDeque<Frame>();
        stack.push(new Frame(root, false));
        while (!stack.isEmpty() && budget > 0) {
            Frame f = stack.pop();
            budget--;
            boolean secure = f.secure || f.node.secure;
            if (WEBVIEW_CLASS.equals(f.node.className)
                    && f.node.visible) {
                webView = f.node;
                webSecure = secure;
                break;
            }
            for (int i = f.node.children.size() - 1; i >= 0; i--) {
                stack.push(new Frame(f.node.children.get(i),
                        secure));
            }
        }
        if (webView == null) return null;
        // Phase 2: the WebView's own subtree. Its children
        // are the page; the WebView itself is not an element.
        List<ElementInfo> elements = new ArrayList<ElementInfo>();
        StringBuilder preview = new StringBuilder();
        int visited = 0;
        int omitted = 0;
        ArrayDeque<Frame> walk = new ArrayDeque<Frame>();
        for (int i = webView.children.size() - 1; i >= 0; i--) {
            walk.push(new Frame(webView.children.get(i),
                    webSecure));
        }
        while (!walk.isEmpty() && budget > 0) {
            Frame f = walk.pop();
            budget--;
            visited++;
            A11yNode n = f.node;
            boolean secure = f.secure || n.secure;
            for (int i = n.children.size() - 1; i >= 0; i--) {
                walk.push(new Frame(n.children.get(i), secure));
            }
            boolean withheld = n.password || secure;
            String label = withheld ? null : NodeSketch.labelOf(n);
            if (label != null
                    && preview.length() < TEXT_PREVIEW_CHARS) {
                if (preview.length() > 0) preview.append(' ');
                preview.append(label);
            }
            if (!isInteractive(n)) continue;
            if (elements.size() >= MAX_ELEMENTS) {
                omitted++;
                continue;
            }
            elements.add(toElement(elements.size() + 1, n, label));
        }
        String text = preview.length() > TEXT_PREVIEW_CHARS
                ? preview.substring(0, TEXT_PREVIEW_CHARS)
                : preview.toString();
        return new Result(elements, text, visited, omitted);
    }

    /** The map contract's admission rule: an element is
     *  something the page offers to act on — clickable,
     *  editable or checkable — and visible. (What the eyes
     *  admit, the loop still refuses to act on; admission is
     *  about what the planner may SEE.) */
    static boolean isInteractive(A11yNode n) {
        return n.visible
                && (n.clickable || n.editable || n.checkable);
    }

    /** One converted element. The fields are the tree's own
     *  declarations mapped onto the DOM map's vocabulary —
     *  nothing is inferred beyond the class/flag mapping
     *  below, and there is no href to carry: the tree does
     *  not declare destinations (the R6 evidence), so an
     *  a11y element never pretends to know where it leads. */
    private static ElementInfo toElement(int number, A11yNode n,
                                         String label) {
        ElementInfo e = new ElementInfo();
        e.number = number;
        e.source = SOURCE;
        e.name = (label == null) ? "" : label;
        String cls = (n.className == null) ? "" : n.className;
        if (n.editable) {
            e.tag = "input";
            e.type = n.password ? "password" : "text";
            e.role = "textbox";
        } else if (n.checkable) {
            e.tag = "input";
            e.type = cls.contains("Radio") ? "radio" : "checkbox";
            e.role = e.type;
            e.checked = n.checked;
        } else if (cls.endsWith("Spinner")) {
            e.tag = "select";
            e.role = "combobox";
        } else if (cls.endsWith("Button")) {
            e.tag = "button";
            e.role = "button";
        } else {
            // A clickable container or text surface — the DOM
            // map's cursor-pointer harvest is its analogue.
            e.tag = "div";
        }
        return e;
    }
}
