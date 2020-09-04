/**
 * Copyright 2012-2020 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.Util.checkNotNull;

/**
 * TODO：重要，同步方法调用处理器
 * 1. 通过方法参数，使用工厂构建出一个RequestTemplate请求模板， 这里会解析出@RequestLine, @Param注解
 * 2. 从方法参数里拿到请求选项 Options
 * 3. executeAndDecode(template, options) 执行发送Http请求，并且完成结果解码 这个步骤比较复杂
 *    1.把请求模板转换为请求对象 feign.Request
 *       1. 执行所有的拦截器RequestInterceptor，完成对请求模板的定制
 *       2. 调用目标target, 把请求模板转为Request, target.apply(template);
 *    2. 发送http请求，client.execute(request, options), 得到一个response对象。
 *    3. 解析此response对象，解析后return, 返回object ,可能是response实例，也可能是decode解码后的任意类型
 * 4. 发送Http请求若没有错误，那就结束了，否则进行重试
 */
final class SynchronousMethodHandler implements MethodHandler {

  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;
  /**
   * 方法元信息
   */
  private final MethodMetadata metadata;
  /**
   * 目标，也就是最终真正构建HTTP请求Request的实例 一般为HardCodedTarget
   */
  private final Target<?> target;
  /**
   * 负责最终请求的发送， 默认传进来的是JDK的，效率比较低
   */
  private final Client client;
  /**
   * 负责重试，默认传进来的是Default, 有重试机制
   */
  private final Retryer retryer;
  /**
   * TODO: 请求拦截器，它会在target.apply(template); 也就是模板 -> 请求的转换之前完成拦截
   */
  private final List<RequestInterceptor> requestInterceptors;
  /**
   * 打印日志
   */
  private final Logger logger;
  private final Logger.Level logLevel;
  /**
   * 构建请求模板的工厂，对于请求模板，有多种构建方式，内部会用到可能多个编码器
   */
  private final RequestTemplate.Factory buildTemplateFromArgs;
  /**
   * 请求参数，比如连接超时时间，请求超时时间等
   */
  private final Options options;
  private final ExceptionPropagationPolicy propagationPolicy;

  // only one of decoder and asyncResponseHandler will be non-null
  /**
   * 解码器，对response进行解码
   */
  private final Decoder decoder;
  /**
   * TODO: 异步响应处理器
   */
  private final AsyncResponseHandler asyncResponseHandler;


  private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer,
      List<RequestInterceptor> requestInterceptors, Logger logger,
      Logger.Level logLevel, MethodMetadata metadata,
      RequestTemplate.Factory buildTemplateFromArgs, Options options,
      Decoder decoder, ErrorDecoder errorDecoder, boolean decode404,
      boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy,
      boolean forceDecoding) {

    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors =
        checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.propagationPolicy = propagationPolicy;

    if (forceDecoding) {
      // internal only: usual handling will be short-circuited, and all responses will be passed to
      // decoder directly!
      this.decoder = decoder;
      this.asyncResponseHandler = null;
    } else {
      this.decoder = null;
      this.asyncResponseHandler = new AsyncResponseHandler(logLevel, logger, decoder, errorDecoder,
          decode404, closeAfterDecode);
    }
  }

  @Override
  public Object invoke(Object[] argv) throws Throwable {
    // TODO: 根据方法入参，结合工厂构建出一个请求模板
    RequestTemplate template = buildTemplateFromArgs.create(argv);
    // TODO: findOptions():如果方法入参里含有Options类型，这里会被找出来, 如果有多个，只会有一个生效
    Options options = findOptions(argv);
    // TODO: 重试机制，这里克隆了一份
    Retryer retryer = this.retryer.clone();
    while (true) {
      try {
        // TODO: 执行并解码, 到这里就准备执行了
        return executeAndDecode(template, options);
      } catch (RetryableException e) {
        try {
          // TODO: 若抛出异常，那就触发重试逻辑，该逻辑是：如果不重试，该异常会继续抛出
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          Throwable cause = th.getCause();
          if (propagationPolicy == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        if (logLevel != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel);
        }
        // TODO: 如果重试 进行continue
        continue;
      }
    }
  }

  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    // TODO: 这里就是解析request了，在这里面执行了著名的RequestInterceptor的拦截
    // TODO: 通过target将requestTemplate转换为Request
    Request request = targetRequest(template);

    // TODO: 打印日志
    if (logLevel != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel, request);
    }

    Response response;
    // TODO: 开始执行时间
    long start = System.nanoTime();
    try {
      // TODO: client拿着request去执行，如果对feign记录请求日志，可以对client进行包装
      response = client.execute(request, options);
      // ensure the request is set. TODO: remove in Feign 12
      response = response.toBuilder()
          .request(request)
          .requestTemplate(template)
          .build();
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
      }
      throw errorExecuting(request, e);
    }
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);


    if (decoder != null)
      // TODO: 解码喽
      return decoder.decode(response, metadata.returnType());

    // TODO: 这里还用到了CompletableFuture
    CompletableFuture<Object> resultFuture = new CompletableFuture<>();
    asyncResponseHandler.handleResponse(resultFuture, metadata.configKey(), response,
        metadata.returnType(),
        elapsedTime);

    try {
      if (!resultFuture.isDone())
        throw new IllegalStateException("Response handling not done");

      return resultFuture.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause != null)
        throw cause;
      throw e;
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  Request targetRequest(RequestTemplate template) {
    // TODO: 执行requestInterceptor中的apply方法，feign流出来的最重要的一个接口
    for (RequestInterceptor interceptor : requestInterceptors) {
      // TODO: 挨个的执行requestInterceptor
      interceptor.apply(template);
    }
    // TODO: 最后把RequestTemplate转换成了Request
    return target.apply(template);
  }

  Options findOptions(Object[] argv) {
    if (argv == null || argv.length == 0) {
      return this.options;
    }
    // TODO: 查找入参是Options类型的
    return Stream.of(argv)
            // TODO: 查找到Options类型的入参，然后进行强转，之后拿出来第一个
        .filter(Options.class::isInstance)
        .map(Options.class::cast)
        .findFirst()
        .orElse(this.options);
  }

  static class Factory {

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;
    private final boolean forceDecoding;

    Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors,
        Logger logger, Logger.Level logLevel, boolean decode404, boolean closeAfterDecode,
        ExceptionPropagationPolicy propagationPolicy, boolean forceDecoding) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.decode404 = decode404;
      this.closeAfterDecode = closeAfterDecode;
      this.propagationPolicy = propagationPolicy;
      this.forceDecoding = forceDecoding;
    }

    public MethodHandler create(Target<?> target,
                                MethodMetadata md,
                                RequestTemplate.Factory buildTemplateFromArgs,
                                Options options,
                                Decoder decoder,
                                ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger,
          logLevel, md, buildTemplateFromArgs, options, decoder,
          errorDecoder, decode404, closeAfterDecode, propagationPolicy, forceDecoding);
    }
  }
}
