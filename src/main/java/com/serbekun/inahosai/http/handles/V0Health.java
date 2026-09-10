package com.serbekun.inahosai.http.handles;

import com.serbekun.inahosai.domain.http.dto.V0HealthGetRes;

import io.javalin.Javalin;

/**
 * <p>Handles the GET request to the <code>/api/v0/health</code> endpoint.</p>
 *
 * <p>Simple server health check endpoint.</p>
 *
 * <p><strong>Always returns:</strong></p>
 * <pre><code>{
 *   "healthy": true
 * }</code></pre>
 *
 * <p>If the server responds, it is considered alive and operational.</p>
 */
public class V0Health implements HttpHandler {

    @Override
    public void register(Javalin svr) {
        svr.get("/api/v0/health", ctx -> {
            ctx.json(new V0HealthGetRes(true));
        });
    }
}