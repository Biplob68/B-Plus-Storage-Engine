package database.engine.bplus.tree;

import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitPolicyTest {

    private static int sum(int[] sizes, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            total += sizes[i];
        }
        return total;
    }

    @Test
    void equalSizesCutDownTheMiddle() {
        int[] sizes = {10, 10, 10, 10};

        assertThat(SplitPolicy.chooseSplitIndex(sizes, 100)).isEqualTo(2);
    }

    @Test
    void picksTheMoreBalancedOfTheTwoCandidates() {
        // running: 309 1218 1477 2386 3195 3604 4313, half 2156, so the crossing is entry 3
        int[] sizes = {309, 909, 259, 909, 809, 409, 709};

        int splitIndex = SplitPolicy.chooseSplitIndex(sizes, SlottedPage.USABLE_BYTES);

        assertThat(splitIndex).isEqualTo(4);
        assertThat(sum(sizes, 0, splitIndex)).isEqualTo(2386);
        assertThat(sum(sizes, splitIndex, sizes.length)).isEqualTo(1927);
    }

    @Test
    void balancesBytesWhereBalancingCountWouldOverflow() {
        int[] sizes = {20, 20, 20, 20, 1018, 1018, 1018, 1018, 1018};
        int capacity = SlottedPage.USABLE_BYTES;

        int byCount = sizes.length / 2;
        assertThat(sum(sizes, byCount, sizes.length))
                .as("splitting on count would overflow the right half")
                .isGreaterThan(capacity);

        int splitIndex = SplitPolicy.chooseSplitIndex(sizes, capacity);

        assertThat(sum(sizes, 0, splitIndex)).isLessThanOrEqualTo(capacity);
        assertThat(sum(sizes, splitIndex, sizes.length)).isLessThanOrEqualTo(capacity);
    }

    @Test
    void neverLeavesAHalfEmpty() {
        // The crossing lands on entry 0, so the only usable cut is after it.
        int[] sizes = {60, 60};

        assertThat(SplitPolicy.chooseSplitIndex(sizes, 100)).isEqualTo(1);
    }

    @Test
    void aHugeFirstEntryStillSplits() {
        int[] sizes = {90, 5, 5, 5, 5};

        int splitIndex = SplitPolicy.chooseSplitIndex(sizes, 100);

        assertThat(splitIndex).isEqualTo(1);
        assertThat(sum(sizes, 0, splitIndex)).isEqualTo(90);
        assertThat(sum(sizes, splitIndex, sizes.length)).isEqualTo(20);
    }

    @Test
    void aHugeLastEntryStillSplits() {
        int[] sizes = {5, 5, 5, 5, 90};

        int splitIndex = SplitPolicy.chooseSplitIndex(sizes, 100);

        assertThat(sum(sizes, 0, splitIndex)).isLessThanOrEqualTo(100);
        assertThat(sum(sizes, splitIndex, sizes.length)).isLessThanOrEqualTo(100);
    }

    @Test
    void throwsWhenNoCutFits() {
        // 95 cannot share a page with either neighbour group: 20+95 and 95+10 both overflow.
        int[] sizes = {10, 10, 95, 10};

        assertThatThrownBy(() -> SplitPolicy.chooseSplitIndex(sizes, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no split fits");
    }

    @Test
    void needsAtLeastTwoEntries() {
        assertThatThrownBy(() -> SplitPolicy.chooseSplitIndex(new int[] {10}, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
        assertThatThrownBy(() -> SplitPolicy.chooseSplitIndex(new int[0], 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everySplitOfARealisticPageLeavesBothHalvesUsable() {
        int capacity = SlottedPage.USABLE_BYTES;

        for (int valueLength = 0; valueLength <= 900; valueLength += 37) {
            int entry = SlottedPage.entrySize(8, valueLength);
            int count = capacity / entry + 1; // one more than the page can hold
            if (count < 2) {
                continue;
            }
            int[] sizes = new int[count];
            java.util.Arrays.fill(sizes, entry);

            int splitIndex = SplitPolicy.chooseSplitIndex(sizes, capacity);

            assertThat(splitIndex).as("value length %d", valueLength).isBetween(1, count - 1);
            assertThat(sum(sizes, 0, splitIndex)).isLessThanOrEqualTo(capacity);
            assertThat(sum(sizes, splitIndex, count)).isLessThanOrEqualTo(capacity);
        }
    }
}
