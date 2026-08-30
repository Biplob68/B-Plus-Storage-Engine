package database.engine.bplus.tree;

import database.engine.bplus.page.SlottedPage;

import java.util.ArrayList;
import java.util.List;


record Entry(byte[] key, byte[] value) {

    static List<Entry> readAll(SlottedPage leaf) {
        List<Entry> entries = new ArrayList<>(leaf.cellCount());
        for (int i = 0; i < leaf.cellCount(); i++) {
            entries.add(new Entry(leaf.key(i), leaf.value(i)));
        }
        return entries;
    }

    static int[] sizesOf(List<Entry> entries) {
        int[] sizes = new int[entries.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = entries.get(i).size();
        }
        return sizes;
    }

    static int totalSize(List<Entry> entries) {
        int total = 0;
        for (Entry entry : entries) {
            total += entry.size();
        }
        return total;
    }

    static void rewriteAll(SlottedPage page, List<Entry> entries) {
        for (int i = page.cellCount() - 1; i >= 0; i--) {
            page.deleteCell(i);
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            page.insertCell(i, entry.key(), entry.value());
        }
    }

    int size() {
        return SlottedPage.entrySize(key.length, value.length);
    }
}
