package com.astral.system.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** Weighted selection without replacement; memory is proportional to candidates, never their weights. */
public final class WeightedCandidates<T> {
    private final List<T> remaining;
    private final Function<T, Integer> weight;
    private long total;

    public WeightedCandidates(List<T> candidates, Function<T, Integer> weight) {
        this.remaining = new ArrayList<>(candidates);
        this.weight = weight;
        for (T candidate : remaining) total += weightOf(candidate);
    }

    public boolean hasNext() {
        return !remaining.isEmpty();
    }

    public T next() {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        long selected = ThreadLocalRandom.current().nextLong(total);
        for (int index = 0; index < remaining.size(); index++) {
            T candidate = remaining.get(index);
            int candidateWeight = weightOf(candidate);
            if (selected < candidateWeight) {
                remaining.remove(index);
                total -= candidateWeight;
                return candidate;
            }
            selected -= candidateWeight;
        }
        throw new IllegalStateException("Candidate weights changed during selection");
    }

    private int weightOf(T candidate) {
        Integer value = weight.apply(candidate);
        return value != null && value > 0 ? value : 1;
    }
}
