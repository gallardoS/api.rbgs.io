package io.rbgs.api.matches;

public enum ControlledMatchKind {
    ROSTER_CONTROLLED(MatchEvidencePolicy.FULL_ROSTER),
    CAPTURE_ONLY(MatchEvidencePolicy.CAPTURE_ONLY);

    private final MatchEvidencePolicy evidencePolicy;

    ControlledMatchKind(MatchEvidencePolicy evidencePolicy) { this.evidencePolicy = evidencePolicy; }
    public MatchEvidencePolicy evidencePolicy() { return evidencePolicy; }
}
