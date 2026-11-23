---
layout: post
title: "Thirty-One Nulls"
date: 2026-11-23
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [java-records, testing, constructor-fragility, builder-pattern, pipeline]
series: issue-1213-fix-agentdescriptor-constructor
---

# Thirty-One Nulls

The `AgentDescriptor` record has thirty-one fields. When someone added the thirty-first — `RoleProfile roleProfile` — four test files in `SituationalPersonality` stopped compiling. The fix was adding `, null` to each constructor call. Four lines changed, two files touched.

The interesting question is why those were the only casualties.

## Why tests broke and production didn't

Production code constructs `AgentDescriptor` via the builder:

```java
var descriptor = AgentDescriptor.builder()
        .agentId("agent-1")
        .name("Agent One")
        .slot("test")
        .tenancyId("tenant-1")
        .build();
```

Adding a field to the record doesn't break any builder call. Unset fields default to null. The builder is immune to positional changes.

The test files used the raw record constructor:

```java
return new AgentDescriptor(AGENT, "Frida", "Artist", "1.0",
        null, null, null, null, null, null, null, null, null,
        "test-slot", null, "creator-artist", null, null,
        List.of(), disposition,
        null, null, TENANT, null, null, null,
        List.of(), List.of(), List.of(), Map.of());
```

Thirty arguments, most of them null. When the record gained a thirty-first field, the compiler rejected every call that passed thirty. The error message helpfully lists all thirty-one expected types — a wall of text that tells you nothing except "count mismatch".

## The pattern

Once a record passes about ten fields, raw constructor usage in tests becomes a maintenance hazard. Every new field breaks every call site. The builder costs a few extra lines but absorbs change without propagation. For test fixtures especially — where most fields are null stubs — the builder makes the intent visible. You see `agentId("agent-1")` instead of counting nulls to figure out which parameter you're setting.

The `AgentDescriptor` builder already exists. These four test methods just didn't use it.

## Pipeline archaeology

The session started with an unrelated puzzle. The work pipeline was in a confused state: the workspace repo was still on the previous branch (`issue-1167-per-agent-retention-adapt`) while the project repo had been landed to main. The `.queue` file had an uncommitted change activating #1213. The alignment repair scripts produced no output, and `work.py continue` completed successfully while reporting the wrong branch.

Diagnosis: `work-end` lands branches to main in the project repo but doesn't switch the workspace back or commit the queue activation. Filed as [soredium#628](https://github.com/Hortora/soredium/issues/628). Manual branch creation was needed to unblock the session — not ideal, but the fix itself took about two minutes once the state was understood.

Two lessons from one session: positional constructors don't scale, and ceremony gaps compound across sessions.
