package io.casehub.neocortex.rag;

import java.util.List;

public interface QueryExpander {
    List<RetrievalQuery> expand(RetrievalQuery query);
}
