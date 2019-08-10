/*
 * Copyright (c) 2019 amy, All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. Neither the name of the copyright holder nor the names of its contributors
 *     may be used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 *  FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 *  DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *  CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 *  OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 *  OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.mewna.catnip.rest.requester;

import com.grack.nanojson.*;
import com.mewna.catnip.Catnip;
import com.mewna.catnip.entity.impl.lifecycle.RestRatelimitHitImpl;
import com.mewna.catnip.extension.Extension;
import com.mewna.catnip.extension.hook.CatnipHook;
import com.mewna.catnip.rest.MultipartBodyPublisher;
import com.mewna.catnip.rest.ResponseException;
import com.mewna.catnip.rest.ResponsePayload;
import com.mewna.catnip.rest.RestPayloadException;
import com.mewna.catnip.rest.Routes.Route;
import com.mewna.catnip.rest.ratelimit.RateLimiter;
import com.mewna.catnip.shard.LifecycleEvent.Raw;
import com.mewna.catnip.util.CatnipMeta;
import com.mewna.catnip.util.Utils;
import com.mewna.catnip.util.rx.RxHelpers;
import io.reactivex.Observable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.tuple.ImmutablePair;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient.Builder;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.mewna.catnip.rest.Routes.HttpMethod.GET;
import static com.mewna.catnip.rest.Routes.HttpMethod.PUT;

@SuppressWarnings("WeakerAccess")
public abstract class AbstractRequester implements Requester {
    public static final BodyPublisher EMPTY_BODY = BodyPublishers.noBody();
    
    protected final RateLimiter rateLimiter;
    protected final Builder clientBuilder;
    protected Catnip catnip;
    
    public AbstractRequester(@Nonnull final RateLimiter rateLimiter, @Nonnull final Builder clientBuilder) {
        this.rateLimiter = rateLimiter;
        this.clientBuilder = clientBuilder;
    }
    
    @Override
    public void catnip(@Nonnull final Catnip catnip) {
        this.catnip = catnip;
        rateLimiter.catnip(catnip);
    }
    
    @Nonnull
    @Override
    public Observable<ResponsePayload> queue(@Nonnull final OutboundRequest r) {
        final CompletableFuture<ResponsePayload> future = new CompletableFuture<>();
        final Bucket bucket = getBucket(r.route());
        // Capture stacktrace if possible
        final StackTraceElement[] stacktrace;
        if(catnip.captureRestStacktraces()) {
            stacktrace = Thread.currentThread().getStackTrace();
        } else {
            stacktrace = new StackTraceElement[0];
        }
        bucket.queueRequest(new QueuedRequest(r, r.route(), future, bucket, stacktrace));
        return RxHelpers.futureToObservable(future)
                .subscribeOn(catnip.rxScheduler())
                .observeOn(catnip.rxScheduler());
    }
    
    @Nonnull
    @CheckReturnValue
    protected abstract Bucket getBucket(@Nonnull Route route);
    
    protected void executeRequest(@Nonnull final QueuedRequest request) {
        Route route = request.route();
        // Compile route for usage
        for(final Entry<String, String> stringStringEntry : request.request().params().entrySet()) {
            route = route.compile(stringStringEntry.getKey(), stringStringEntry.getValue());
        }
        if(request.request().buffers() != null) {
            handleRouteBufferBodySend(route, request);
        } else {
            handleRouteJsonBodySend(route, request);
        }
    }
    
    protected void handleRouteBufferBodySend(@Nonnull final Route finalRoute, @Nonnull final QueuedRequest request) {
        try {
            final MultipartBodyPublisher publisher = new MultipartBodyPublisher();
            final OutboundRequest r = request.request();
            for(int index = 0; index < r.buffers().size(); index++) {
                final ImmutablePair<String, byte[]> pair = r.buffers().get(index);
                publisher.addPart("file" + index, pair.left, pair.right);
            }
            if(r.object() != null) {
                for(final Extension extension : catnip.extensionManager().extensions()) {
                    for(final CatnipHook hook : extension.hooks()) {
                        r.object(hook.rawRestSendObjectHook(finalRoute, r.object()));
                    }
                }
                publisher.addPart("payload_json", JsonWriter.string(r.object()));
            } else if(r.array() != null) {
                publisher.addPart("payload_json", JsonWriter.string(r.array()));
            } else {
                
                publisher.addPart("payload_json",
                        JsonWriter.string()
                                .object()
                                .nul("content")
                                .nul("embed")
                                .end()
                                .done());
            }
            executeHttpRequest(finalRoute, publisher.build(), request, "multipart/form-data;boundary=" + publisher.getBoundary());
        } catch(final Exception e) {
            catnip.logAdapter().error("Failed to send multipart request", e);
        }
    }
    
    protected void handleRouteJsonBodySend(@Nonnull final Route finalRoute, @Nonnull final QueuedRequest request) {
        final OutboundRequest r = request.request();
        final String encoded;
        if(r.object() != null) {
            for(final Extension extension : catnip.extensionManager().extensions()) {
                for(final CatnipHook hook : extension.hooks()) {
                    r.object(hook.rawRestSendObjectHook(finalRoute, r.object()));
                }
            }
            encoded = JsonWriter.string(r.object());
        } else if(r.array() != null) {
            encoded = JsonWriter.string(r.array());
        } else {
            encoded = null;
        }
        executeHttpRequest(finalRoute, encoded == null ? BodyPublishers.noBody() : BodyPublishers.ofString(encoded), request, "application/json");
    }
    
    protected void executeHttpRequest(@Nonnull final Route route, @Nullable final BodyPublisher body,
                                      @Nonnull final QueuedRequest request, @Nonnull final String mediaType) {
        final HttpRequest.Builder builder;
        
        if(route.method() == GET) {
            // No body
            builder = HttpRequest.newBuilder(URI.create(API_HOST + API_BASE + route.baseRoute())).GET();
        } else {
            builder = HttpRequest.newBuilder(URI.create(API_HOST + API_BASE + route.baseRoute()))
                    .setHeader("Content-Type", mediaType)
                    .method(route.method().name(), body);
        }
        
        builder.setHeader("User-Agent", "DiscordBot (https://github.com/mewna/catnip, " + CatnipMeta.VERSION + ')');
        
        if(request.request().needsToken()) {
            builder.setHeader("Authorization", "Bot " + catnip.token());
        }
        if(request.request().reason() != null) {
            builder.header(Requester.REASON_HEADER, Utils.encodeUTF8(request.request().reason()));
        }
        if(body == null) {
            // If we don't have a body, then the body param is null, which
            // seems to not set a Content-Length. This explicitly sets it so
            // that we can avoid 411s from Discord.
            builder.setHeader("Content-Length", Long.toString(0L));
        }
        
        // Update request start time as soon as possible
        // See QueuedRequest docs for why we do this
        request.start = System.nanoTime();
        catnip.httpClient().sendAsync(builder.build(), BodyHandlers.ofString())
                .thenAccept(res -> {
                    final int code = res.statusCode();
                    final String message = "Unavailable to due Java's HTTP client.";
                    final long requestEnd = System.nanoTime();
                    
                    catnip.rxScheduler().scheduleDirect(() ->
                            handleResponse(route, code, message, requestEnd, res.body(), res.headers(), request));
                })
                .exceptionally(e -> {
                    request.bucket.failedRequest(request, e);
                    return null;
                });
    }
    
    protected void handleResponse(@Nonnull final Route route, final int statusCode,
                                  @SuppressWarnings("SameParameterValue") @Nonnull final String statusMessage,
                                  final long requestEnd, final String body, final HttpHeaders headers,
                                  @Nonnull final QueuedRequest request) {
        final String dateHeader = headers.firstValue("Date").orElse(null);
        final long requestDuration = TimeUnit.NANOSECONDS.toMillis(requestEnd - request.start);
        final long timeDifference;
        if(route.method() == PUT && route.baseRoute().contains("/reactions/")) {
            timeDifference = requestDuration;
            catnip.logAdapter().trace("Reaction route, using time difference = request duration = {}", timeDifference);
        } else if(dateHeader == null) {
            timeDifference = requestDuration;
            catnip.logAdapter().trace("No date header, time difference = request duration = {}", timeDifference);
        } else {
            final long now = System.currentTimeMillis();
            // Parsing the date header like this will only be accurate to the
            // second, meaning that reaction routes will be significantly
            // inaccurate using this method, as those routes have ratelimits
            // with millisecond precision. This leads to ex. very slow reaction
            // menus. To solve this, we use the request duration to measure
            // latency instead.
            // TODO: Is there a more accurate way to do this that still
            //  respects the ms-precision ratelimits?
            final long date = OffsetDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                    .toEpochMilli();
            timeDifference = now - date + requestDuration;
            catnip.logAdapter().trace("Have date header, time difference = now - date + request duration = " +
                            "{} - {} + {} = {}",
                    now, date, requestDuration, timeDifference);
        }
        if(statusCode == 429) {
            if(catnip.logLifecycleEvents()) {
                catnip.logAdapter().error(
                        "Hit 429! Route: {}, X-Ratelimit-Global: {}, X-Ratelimit-Limit: {}, X-Ratelimit-Reset: {}",
                        route.baseRoute(),
                        headers.firstValue("X-Ratelimit-Global").orElse(null),
                        headers.firstValue("X-Ratelimit-Limit").orElse(null),
                        headers.firstValue("X-Ratelimit-Reset").orElse(null)
                );
            }
            catnip.dispatchManager().dispatchEvent(Raw.REST_RATELIMIT_HIT,
                    new RestRatelimitHitImpl(route.baseRoute(),
                            Boolean.parseBoolean(headers.firstValue("X-RateLimit-Global").orElse(null)),
                            catnip));
            
            String retry = headers.firstValue("Retry-After").orElse(null);
            if(retry == null || retry.isEmpty()) {
                try {
                    retry = JsonParser.object().from(body).get("retry_after").toString();
                } catch(final JsonParserException e) {
                    throw new IllegalStateException(e);
                }
            }
            final long retryAfter = Long.parseLong(retry);
            if(Boolean.parseBoolean(headers.firstValue("X-RateLimit-Global").orElse(null))) {
                catnip.logAdapter().trace("Updating global bucket due to ratelimit.");
                rateLimiter.updateGlobalRateLimit(System.currentTimeMillis() + timeDifference + retryAfter);
            } else {
                catnip.logAdapter().trace("Updating bucket headers due to ratelimit.");
                updateBucket(route, headers,
                        System.currentTimeMillis() + timeDifference + retryAfter, timeDifference);
            }
            // It should get autodisposed anyway, so we don't need to worry
            // about handling the method result
            //noinspection ResultOfMethodCallIgnored
            rateLimiter.requestExecution(route)
                    .subscribe(() -> executeRequest(request),
                            e -> {
                                final Throwable throwable = new RuntimeException("REST error context");
                                throwable.setStackTrace(request.stacktrace());
                                request.future().completeExceptionally(e.initCause(throwable));
                            });
        } else {
            catnip.logAdapter().trace("Updating bucket headers from successful completion with code {}.", statusCode);
            updateBucket(route, headers, -1, timeDifference);
            request.bucket().requestDone();
            
            ResponsePayload payload = new ResponsePayload(body);
            for(final Extension extension : catnip.extensionManager().extensions()) {
                for(final CatnipHook hook : extension.hooks()) {
                    payload = hook.rawRestReceiveDataHook(route, payload);
                }
            }
            // We got a 4xx, meaning there's errors. Fail the request with this and move on.
            if(statusCode >= 400) {
                catnip.logAdapter().trace("Request received an error code ({} >= 400), processing...", statusCode);
                final JsonObject response = payload.object();
                if(statusCode == 400 && response.getInt("code", -1) > 1000) {
                    // 1000 was just the easiest number to check to skip over http error codes
                    // Discord error codes are all >=10000 afaik, so this should be safe?
                    catnip.logAdapter().trace("Status code 400 + JSON code, creating RestPayloadException...");
                    final Map<String, List<String>> failures = new HashMap<>();
                    response.forEach((key, value) -> {
                        if(value instanceof JsonArray) {
                            final JsonArray arr = (JsonArray) value;
                            final List<String> errorStrings = new ArrayList<>();
                            arr.stream().map(element -> (String) element).forEach(errorStrings::add);
                            failures.put(key, errorStrings);
                        } else if(value instanceof Integer) {
                            failures.put(key, List.of(String.valueOf(value)));
                        } else if(value instanceof String) {
                            failures.put(key, List.of((String) value));
                        } else {
                            // If we don't know what it is, just stringify it and log a warning so that people can tell us
                            catnip.logAdapter().warn("Got unknown error response type: {} (Please report this!)",
                                    value.getClass().getName());
                            failures.put(key, List.of(String.valueOf(value)));
                        }
                    });
                    final Throwable throwable = new RuntimeException("REST error context");
                    throwable.setStackTrace(request.stacktrace());
                    request.future().completeExceptionally(new RestPayloadException(failures).initCause(throwable));
                } else {
                    catnip.logAdapter().trace("Status code != 400, creating ResponseException...");
                    final String message = response.getString("message", "No message.");
                    final int code = response.getInt("code", -1);
                    final Throwable throwable = new RuntimeException("REST error context");
                    throwable.setStackTrace(request.stacktrace());
                    request.future().completeExceptionally(new ResponseException(route.toString(), statusCode,
                            statusMessage, code, message, response).initCause(throwable));
                }
            } else {
                catnip.logAdapter().trace("Successfully completed request future.");
                request.future().complete(payload);
            }
        }
    }
    
    protected void updateBucket(@Nonnull final Route route, @Nonnull final HttpHeaders headers, final long retryAfter,
                                final long timeDifference) {
        final OptionalLong rateLimitReset = headers.firstValueAsLong("X-RateLimit-Reset");
        final OptionalLong rateLimitRemaining = headers.firstValueAsLong("X-RateLimit-Remaining");
        final OptionalLong rateLimitLimit = headers.firstValueAsLong("X-RateLimit-Limit");
        
        catnip.logAdapter().trace(
                "Updating headers for {} ({}): remaining = {}, limit = {}, reset = {}, retryAfter = {}, timeDifference = {}",
                route, route.ratelimitKey(), rateLimitRemaining.orElse(-1L), rateLimitLimit.orElse(-1L),
                rateLimitReset.orElse(-1L), retryAfter, timeDifference
        );
        
        if(retryAfter > 0) {
            rateLimiter.updateRemaining(route, 0);
            rateLimiter.updateReset(route, retryAfter);
        }
        
        if(route.method() == PUT && route.baseRoute().contains("/reactions/")) {
            rateLimiter.updateLimit(route, 1);
            rateLimiter.updateReset(route, System.currentTimeMillis() + timeDifference + 250);
        } else {
            if(rateLimitReset.isPresent()) {
                rateLimiter.updateReset(route, rateLimitReset.getAsLong() * 1000 + timeDifference);
            }
            
            if(rateLimitLimit.isPresent()) {
                rateLimiter.updateLimit(route, Math.toIntExact(rateLimitLimit.getAsLong()));
            }
        }
        
        if(rateLimitRemaining.isPresent()) {
            rateLimiter.updateRemaining(route, Math.toIntExact(rateLimitRemaining.getAsLong()));
        }
        
        rateLimiter.updateDone(route);
    }
    
    private boolean requiresRequestBody(final String method) {
        // Stolen from OkHTTP
        return method.equals("POST")
                || method.equals("PUT")
                || method.equals("PATCH")
                || method.equals("PROPPATCH") // WebDAV
                || method.equals("REPORT");   // CalDAV/CardDAV (defined in WebDAV Versioning)
    }
    
    protected interface Bucket {
        void queueRequest(@Nonnull QueuedRequest request);
        
        void failedRequest(@Nonnull QueuedRequest request, @Nonnull Throwable failureCause);
        
        void requestDone();
    }
    
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    protected static class QueuedRequest {
        protected final OutboundRequest request;
        protected final Route route;
        protected final CompletableFuture<ResponsePayload> future;
        protected final Bucket bucket;
        protected final StackTraceElement[] stacktrace;
        protected int failedAttempts;
        private long start;
        
        protected void failed() {
            failedAttempts++;
        }
        
        protected boolean shouldRetry() {
            return failedAttempts < 3;
        }
    }
}
