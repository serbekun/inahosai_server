package com.serbekun.inahosai.http.handles;

import io.javalin.Javalin;

/**
 * Base interface for all HTTP handlers/controllers.
 * Each handler registers its own routes with the Javalin app.
 * <p>
 * This allows clean dependency injection — handlers receive
 * the services they need via their constructor, and the wiring
 * happens in a central place (e.g. {@code Main.java}).
 * </p>
 *
 * <pre>
 * public class SearchHandler implements HttpHandler {
 *     private final MakeSearch makeSearch;
 *
 *     public SearchHandler(MakeSearch makeSearch) {
 *         this.makeSearch = makeSearch;
 *     }
 *
 *     &#64;Override
 *     public void register(Javalin app) {
 *         app.post("/api/v0/search", ctx -> {
 *             // handler logic using makeSearch
 *         });
 *     }
 * }
 * </pre>
 */
public interface HttpHandler {
    void register(Javalin svr);
}
