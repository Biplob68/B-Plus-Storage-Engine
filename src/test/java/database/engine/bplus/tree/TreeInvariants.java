package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.util.Bytes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks that a tree is still a tree. Test fixture only.
 *
 * <p>A split that goes wrong corrupts the shape of the tree, not the values in it, so the first
 * sign of it is usually a lookup returning null a thousand inserts later. This says which rule
 * broke and on which page.
 *
 * <pre>
 *   1  every leaf is at the same depth
 *   2  keys ascend across the whole tree, left to right
 *   3  every key in a subtree lies inside the range its separators promise
 *   4  rightSibling from the leftmost leaf visits every leaf, in order, once
 *   5  no page id appears twice
 * </pre>
 */
final class TreeInvariants {

    /** The range a subtree may hold. Null is unbounded; an empty array is a real key, never null. */
    private record Bounds(byte[] lowerInclusive, byte[] upperExclusive) {

        static final Bounds UNBOUNDED = new Bounds(null, null);

        boolean isBelowRange(byte[] key) {
            return lowerInclusive != null && Bytes.compare(key, lowerInclusive) < 0;
        }

        boolean isAboveRange(byte[] key) {
            return upperExclusive != null && Bytes.compare(key, upperExclusive) >= 0;
        }
    }

    private record PageContents(PageType type, List<byte[]> keys, List<Integer> children) {}

    private final PageStore store;
    private final Set<Integer> seenPages = new HashSet<>();
    private final Set<Integer> leafDepths = new HashSet<>();
    private final List<Integer> leavesInKeyOrder = new ArrayList<>();
    private byte[] lastLeafKey;

    private TreeInvariants(PageStore store) {
        this.store = store;
    }

    /** Throws {@link AssertionError} naming the first rule that broke. */
    static void check(PageStore store, int rootPageId) {
        TreeInvariants invariants = new TreeInvariants(store);
        invariants.visit(rootPageId, Bounds.UNBOUNDED, 0);
        invariants.checkLeafDepths();
        invariants.checkSiblingChain();
    }

    private void visit(int pageId, Bounds bounds, int depth) {
        if (!seenPages.add(pageId)) {
            throw fail("page " + pageId + " appears twice in the tree");
        }
        PageContents contents = read(pageId);
        if (contents.type() == PageType.LEAF) {
            visitLeaf(pageId, contents.keys(), bounds, depth);
        } else {
            visitInternal(pageId, contents, bounds, depth);
        }
    }

    private void visitLeaf(int pageId, List<byte[]> keys, Bounds bounds, int depth) {
        leafDepths.add(depth);
        leavesInKeyOrder.add(pageId);

        for (byte[] key : keys) {
            requireInRange(pageId, key, bounds);
            requireAscending(pageId, key);
        }
    }

    private void visitInternal(int pageId, PageContents contents, Bounds bounds, int depth) {
        List<byte[]> separators = contents.keys();
        if (separators.isEmpty()) {
            throw fail("internal page " + pageId + " has no children");
        }
        if (separators.get(0).length != 0) {
            throw fail("internal page " + pageId + " is missing its leftmost child in slot 0");
        }

        for (int i = 1; i < separators.size(); i++) {
            requireInRange(pageId, separators.get(i), bounds);
        }
        for (int i = 0; i < contents.children().size(); i++) {
            visit(contents.children().get(i), childBounds(separators, i, bounds), depth + 1);
        }
    }

    /** Slot 0 inherits the parent's lower bound; every other child is fenced by its own separators. */
    private static Bounds childBounds(List<byte[]> separators, int slotIndex, Bounds parent) {
        byte[] lower = slotIndex == 0 ? parent.lowerInclusive() : separators.get(slotIndex);
        boolean last = slotIndex == separators.size() - 1;
        byte[] upper = last ? parent.upperExclusive() : separators.get(slotIndex + 1);
        return new Bounds(lower, upper);
    }

    private void checkLeafDepths() {
        if (leafDepths.size() > 1) {
            throw fail("leaves are at different depths: " + new TreeSet<>(leafDepths));
        }
    }

    private void checkSiblingChain() {
        List<Integer> chain = new ArrayList<>();
        int pageId = leavesInKeyOrder.get(0);

        while (pageId != Page.NO_PAGE) {
            if (chain.size() >= leavesInKeyOrder.size()) {
                throw fail("sibling chain does not end after " + leavesInKeyOrder.size() + " leaves; it loops");
            }
            chain.add(pageId);
            pageId = rightSiblingOf(pageId);
        }

        if (!chain.equals(leavesInKeyOrder)) {
            throw fail("sibling chain " + chain + " does not match the leaves in key order " + leavesInKeyOrder);
        }
    }

    private void requireInRange(int pageId, byte[] key, Bounds bounds) {
        if (bounds.isBelowRange(key)) {
            throw fail("key " + hex(key) + " in page " + pageId + " is outside its subtree range: below "
                    + hex(bounds.lowerInclusive()));
        }
        if (bounds.isAboveRange(key)) {
            throw fail("key " + hex(key) + " in page " + pageId + " is outside its subtree range: at or above "
                    + hex(bounds.upperExclusive()));
        }
    }

    private void requireAscending(int pageId, byte[] key) {
        if (lastLeafKey != null && Bytes.compare(lastLeafKey, key) >= 0) {
            throw fail("keys are not ascending across leaves: " + hex(lastLeafKey) + " then " + hex(key)
                    + " in page " + pageId);
        }
        lastLeafKey = key;
    }

    private PageContents read(int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            List<byte[]> keys = new ArrayList<>();
            for (int i = 0; i < page.cellCount(); i++) {
                keys.add(page.key(i));
            }
            List<Integer> children = new ArrayList<>();
            if (page.type() == PageType.INTERNAL) {
                InternalNode node = new InternalNode(page);
                for (int i = 0; i < node.childCount(); i++) {
                    children.add(node.childAt(i));
                }
            }
            return new PageContents(page.type(), keys, children);
        } finally {
            store.release(pageId);
        }
    }

    private int rightSiblingOf(int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            if (page.type() != PageType.LEAF) {
                throw fail("sibling chain reached non-leaf page " + pageId);
            }
            return page.rightSibling();
        } finally {
            store.release(pageId);
        }
    }

    private static AssertionError fail(String message) {
        return new AssertionError("tree invariant broken: " + message);
    }

    private static String hex(byte[] key) {
        if (key.length == 0) {
            return "(empty)";
        }
        StringBuilder text = new StringBuilder(2 + key.length * 2);
        text.append("0x");
        for (byte b : key) {
            text.append(String.format("%02X", b));
        }
        return text.toString();
    }
}
