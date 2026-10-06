package io.casehub.neocortex.knowledge;

import java.util.List;

public enum NameEntityMatcher implements EntityMatcher<CachedEntity> {
    INSTANCE;

    @Override
    public MatchResult match(CachedEntity candidate, CachedEntity existing) {
        if (candidate.name() == null || existing.name() == null) {
            return MatchResult.noMatch();
        }
        String a = candidate.name().toLowerCase().strip();
        String b = existing.name().toLowerCase().strip();
        if (a.equals(b)) {
            return new MatchResult(1.0, List.of("name-exact"), MatchTier.DEFINITIVE);
        }
        if (a.contains(b) || b.contains(a)) {
            return new MatchResult(0.6, List.of("name-substring"), MatchTier.MEDIUM);
        }
        return MatchResult.noMatch();
    }
}
