package io.casehub.neocortex.memory.inmem;

import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.MemoryDomain;

record BucketKey(String tenantId, String entityId, MemoryDomain domain) {}
