---
title: REST Endpoint Design Patterns
domain: tech
tags: rest, api, design
---

JAX-RS provides a declarative framework for building RESTful web services in Java through annotations like @Path, @GET, @POST, and @Consumes. These annotations define URL routing, HTTP method handling, and content negotiation without boilerplate code. Resource classes become HTTP endpoints by combining these annotations with method signatures that accept and return domain objects.

Input validation is non-negotiable for production APIs. Bean Validation annotations like @NotNull, @Size, and @Pattern integrate seamlessly with JAX-RS. The container automatically validates incoming request bodies before method invocation. Validation failures trigger ConstraintViolationException, which should be mapped to 400 Bad Request responses with detailed error messages.

Error handling determines API usability. RFC 7807 defines a standard problem detail format that communicates errors clearly. A problem detail document includes a type URI, human-readable title, HTTP status code, and optional detail text. JAX-RS exception mappers convert application exceptions to RFC 7807 responses, providing consistent error semantics across all endpoints.

Resource methods should return appropriate status codes: 200 for successful reads, 201 for creation with Location header, 204 for successful updates with no response body, 404 for missing resources, and 409 for conflicts. Clear status codes eliminate ambiguity and improve client error handling.
