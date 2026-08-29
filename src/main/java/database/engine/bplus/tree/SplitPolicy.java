package database.engine.bplus.tree;


final class SplitPolicy {

    private SplitPolicy() {
        throw new AssertionError("no instances");
    }


    static int chooseSplitIndex(int[] entrySizes, int capacityBytes) {
        if (entrySizes.length < 2) {
            throw new IllegalArgumentException("need at least 2 entries to split, got " + entrySizes.length);
        }
        int[] runningTotals = runningTotals(entrySizes);
        int total = runningTotals[entrySizes.length];
        int crossing = firstCrossingOfHalf(runningTotals, total);

        // Only these two can ever win. The cut at the crossing always has a left side under half,
        // so moving left only grows the side that was already too big, and moving right overflows
        // the left. If neither fits, no cut does.
        int bestIndex = -1;
        int bestImbalance = Integer.MAX_VALUE;
        for (int splitIndex : new int[]{crossing, crossing + 1}) {
            if (!bothHalvesFit(runningTotals, total, splitIndex, capacityBytes)) {
                continue;
            }
            int imbalance = imbalance(runningTotals, total, splitIndex);
            if (imbalance < bestImbalance) {
                bestIndex = splitIndex;
                bestImbalance = imbalance;
            }
        }
        if (bestIndex < 0) {
            throw new IllegalStateException("no split fits: " + total + " bytes over " + entrySizes.length
                    + " entries cannot be cut into two pages of " + capacityBytes);
        }
        return bestIndex;
    }


    private static int[] runningTotals(int[] entrySizes) {
        int[] totals = new int[entrySizes.length + 1];
        for (int i = 0; i < entrySizes.length; i++) {
            totals[i + 1] = totals[i] + entrySizes[i];
        }
        return totals;
    }

    private static int firstCrossingOfHalf(int[] runningTotals, int total) {
        int half = total / 2;
        for (int i = 0; i < runningTotals.length - 1; i++) {
            if (runningTotals[i + 1] >= half) {
                return i;
            }
        }
        return runningTotals.length - 2;
    }

    private static boolean bothHalvesFit(int[] runningTotals, int total, int splitIndex, int capacityBytes) {
        int lastIndex = runningTotals.length - 1;
        if (splitIndex < 1 || splitIndex > lastIndex - 1) {
            return false; // one half would be empty
        }
        return leftBytes(runningTotals, splitIndex) <= capacityBytes
                && rightBytes(runningTotals, total, splitIndex) <= capacityBytes;
    }

    private static int imbalance(int[] runningTotals, int total, int splitIndex) {
        return Math.abs(leftBytes(runningTotals, splitIndex) - rightBytes(runningTotals, total, splitIndex));
    }

    private static int leftBytes(int[] runningTotals, int splitIndex) {
        return runningTotals[splitIndex];
    }

    private static int rightBytes(int[] runningTotals, int total, int splitIndex) {
        return total - runningTotals[splitIndex];
    }
}
