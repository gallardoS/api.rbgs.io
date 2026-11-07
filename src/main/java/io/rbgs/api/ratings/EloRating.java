package io.rbgs.api.ratings;

final class EloRating {
    static final String VERSION = "elo-v1";

    private EloRating() {}

    static int delta(double score, double own, double opponent) {
        double expected = 1 / (1 + StrictMath.pow(10, (opponent - own) / 400));
        double raw = 32 * (score - expected);
        return (int) Math.copySign(Math.floor(Math.abs(raw) + 0.5), raw);
    }

    static int apply(int previous, int delta) {
        return Math.toIntExact(Math.max(0, (long) previous + delta));
    }
}
