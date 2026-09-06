package database.engine.bplus.tree;

import database.engine.bplus.page.SlottedPage;

import java.util.ArrayList;
import java.util.List;

record Child(byte[] separator, int pageId) {


    static final byte[] LEFTMOST = new byte[0];

    private static final int PAGE_ID_BYTES = 4;

    static List<Child> readAll(InternalNode node) {
        List<Child> children = new ArrayList<>(node.childCount());
        for (int i = 0; i < node.childCount(); i++) {
            children.add(new Child(node.separatorAt(i), node.childAt(i)));
        }
        return children;
    }

    static int[] sizesOf(List<Child> children) {
        int[] sizes = new int[children.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = children.get(i).size();
        }
        return sizes;
    }

    static int totalSize(List<Child> children) {
        int total = 0;
        for (Child child : children) {
            total += child.size();
        }
        return total;
    }

    static void writeAll(InternalNode node, List<Child> children) {
        node.clear();
        node.setLeftmostChild(children.get(0).pageId());
        for (int i = 1; i < children.size(); i++) {
            node.insertSeparator(children.get(i).separator(), children.get(i).pageId());
        }
    }

    Child asLeftmost() {
        return new Child(LEFTMOST, pageId);
    }

    int size() {
        return SlottedPage.entrySize(separator.length, PAGE_ID_BYTES);
    }
}
