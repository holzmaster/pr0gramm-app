package com.pr0gramm.app;

import android.content.Context;

import com.google.common.base.Stopwatch;
import com.google.common.reflect.Reflection;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.pr0gramm.app.api.InstantDeserializer;
import com.pr0gramm.app.api.pr0gramm.Api;
import com.pr0gramm.app.services.GifToVideoService;
import com.pr0gramm.app.services.HttpProxyService;
import com.pr0gramm.app.services.MyGifToVideoService;
import com.pr0gramm.app.services.ProxyService;
import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.picasso.Downloader;
import com.squareup.picasso.OkHttpDownloader;
import com.squareup.picasso.Picasso;

import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.OkClient;
import retrofit.converter.GsonConverter;
import retrofit.http.GET;
import roboguice.inject.SharedPreferencesName;
import rx.Observable;

/**
 */
@SuppressWarnings("UnusedDeclaration")
public class Pr0grammModule extends AbstractModule {
    private static final Logger logger = LoggerFactory.getLogger(Pr0grammModule.class);

    @Override
    protected void configure() {
    }

    @Provides
    @Singleton
    public Api api(Settings settings, LoginCookieHandler cookieHandler, OkHttpClient client) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Instant.class, new InstantDeserializer())
                .create();

        String host = "http://pr0gramm.com";

        // activate this to use a mock
        // host = "http://demo8733773.mockable.io";

        Api api = new RestAdapter.Builder()
                .setEndpoint(host)
                .setConverter(new GsonConverter(gson))
                .setLogLevel(RestAdapter.LogLevel.BASIC)
                .setLog(new LoggerAdapter(LoggerFactory.getLogger(Api.class)))
                .setClient(new OkClient(client))
                .build()
                .create(Api.class);

        // proxy to add the nonce if not provided
        return Reflection.newProxy(Api.class, (proxy, method, args) -> {
            Class<?>[] params = method.getParameterTypes();
            if (params.length > 0 && params[0] == Api.Nonce.class) {
                if (args.length > 0 && args[0] == null) {

                    // inform about failure.
                    try {
                        args = Arrays.copyOf(args, args.length);
                        args[0] = cookieHandler.getNonce();

                    } catch (Throwable error) {
                        AndroidUtility.logToCrashlytics(error);

                        if (method.getReturnType() == Observable.class) {
                            // don't fail here, but fail in the resulting observable.
                            return Observable.error(error);

                        } else {
                            throw error;
                        }
                    }
                }
            }

            if (method.getReturnType() == Observable.class) {
                // check if this is a get for retry.
                GET get = method.getAnnotation(GET.class);
                final int retryCount = get != null ? 2 : 0;

                return invokeWithRetry(api, method, args, retryCount);
            }

            try {
                return method.invoke(api, args);
            } catch (InvocationTargetException targetError) {
                throw targetError.getCause();
            }
        });
    }

    @SuppressWarnings({"unchecked", "RedundantCast"})
    private static Observable<Object> invokeWithRetry(Api api, Method method, Object[] args, int retryCount)
            throws IllegalAccessException, InvocationTargetException {

        Observable<Object> result = (Observable<Object>) method.invoke(api, args);
        for (int i = 0; i < retryCount; i++) {
            result = result.onErrorResumeNext(ignored -> {
                try {
                    // give the server a small grace period before trying again.
                    Uninterruptibles.sleepUninterruptibly(500, TimeUnit.MILLISECONDS);

                    logger.warn("perform retry, calling method {} again", method);
                    return (Observable<Object>) method.invoke(api, args);
                } catch (Exception error) {
                    return Observable.error(error);
                }
            });
        }

        return result;
    }

    @Provides
    @Singleton
    public GifToVideoService gifToVideoService(OkHttpClient client) {
        return new MyGifToVideoService(client);
    }

    @Provides
    @Singleton
    public OkHttpClient okHttpClient(Context context, LoginCookieHandler cookieHandler) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "imgCache");

        OkHttpClient client = new OkHttpClient();
        client.setCache(new Cache(cacheDir, 100 * 1024 * 1024));
        client.setCookieHandler(cookieHandler);
        client.setSocketFactory(new SmallBufferSocketFactory());

        final Logger logger = LoggerFactory.getLogger(OkHttpClient.class);
        client.networkInterceptors().add(chain -> {
            Stopwatch watch = Stopwatch.createStarted();
            Request request = chain.request();

            logger.info("performing http request for " + request.urlString());
            try {
                Response response = chain.proceed(request);
                logger.info("{} ({}) took {}", request.urlString(), response.code(), watch);
                return response;

            } catch (Exception error) {
                logger.warn("{} produced error: {}", request.urlString(), error);
                throw error;
            }
        });

        return client;
    }

    @Provides
    @Singleton
    public Downloader downloader(OkHttpClient client) {
        return new OkHttpDownloader(client);
    }

    @Provides
    @Singleton
    public Picasso picasso(Context context, Downloader downloader) {
        return new Picasso.Builder(context)
                .memoryCache(GuavaPicassoCache.defaultSizedGuavaCache())
                .downloader(downloader)
                .build();
    }

    @Provides
    @Singleton
    public ProxyService proxyService(Settings settings, OkHttpClient httpClient) {
        for (int i = 0; i < 10; i++) {
            try {
                HttpProxyService proxy = new HttpProxyService(httpClient);
                proxy.start();

                // return the proxy
                return url -> settings.useProxy() ? proxy.proxy(url) : url;

            } catch (IOException ioError) {
                logger.warn("Could not open proxy: {}", ioError.toString());
            }
        }

        // if we could not open a proxy, just go with no proxy.
        return url -> url;
    }

    @Provides
    @SharedPreferencesName
    public String sharedPreferencesName() {
        return "pr0gramm";
    }

    private static boolean isNetworkError(Throwable error) {
        if (error instanceof RetrofitError) {
            RetrofitError httpError = (RetrofitError) error;

            RetrofitError.Kind kind = httpError.getKind();
            return kind == RetrofitError.Kind.HTTP || kind == RetrofitError.Kind.NETWORK;
        }

        return false;
    }
}
