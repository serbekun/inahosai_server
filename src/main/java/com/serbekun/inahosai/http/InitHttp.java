package com.serbekun.inahosai.http;

import java.util.List;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serbekun.inahosai.domain.http.dto.ErrorRes;
import com.serbekun.inahosai.http.handles.HttpHandler;

public class InitHttp {

    private static final Logger log = LoggerFactory.getLogger(InitHttp.class);

    private final Javalin svr;
    private final int port;
    private final List<HttpHandler> handlers;

    public InitHttp(Javalin svr, int port, List<HttpHandler> handlers) {
        this.svr = svr;
        this.port = port;
        this.handlers = handlers;
    }

    /**
     * Init http server — registers all handlers and starts the server.
     */
    public void initHttp() {
        if (port < 0 || port > 65535) {
            log.error("Error invalid port number");
            return;
        }

        // Register all handlers — each handler registers its own routes.
        for (HttpHandler handler : handlers) {
            handler.register(svr);
        }

        // Global exception handler — catches any unhandled exception thrown while
        // processing a request, logs it for diagnostics, and returns a uniform
        // 500 Internal Server Error JSON response so the server stays stable.
        svr.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception while processing {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorRes("Internal server error"));
        });

        // Start server and create other thread for server.
        addShutdownHook(svr);

        Thread httpServerThread = new Thread(() -> {
            svr.start(port);
        }, "http-server-thread");

        httpServerThread.start();
    }

    /**
     * Add shutdown hook for when we 
     * press ctr + c server will be safely stopped.
     * 
     * @param svr
     */
    private static void addShutdownHook(Javalin svr) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // log.info("Shutting down server...");
            svr.stop();
        }, "shutdown-hook"));
    }
}
