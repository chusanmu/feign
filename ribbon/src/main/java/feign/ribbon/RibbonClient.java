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
package feign.ribbon;

import com.netflix.client.ClientException;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.net.URI;

/**
 * RibbonClient can be used in Feign builder to activate smart routing and resiliency capabilities
 * provided by Ribbon. Ex.
 * 
 * <pre>
 * MyService api = Feign.builder.client(RibbonClient.create()).target(MyService.class,
 *     &quot;http://myAppProd&quot;);
 * </pre>
 * 
 * Where {@code myAppProd} is the ribbon client name and {@code myAppProd.ribbon.listOfServers}
 * configuration is set.
 */
public class RibbonClient implements Client {

  /**
   * 也是采用的委托，委派模式
   */
  private final Client delegate;
  private final LBClientFactory lbClientFactory;


  public static RibbonClient create() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * @deprecated Use the {@link RibbonClient#create()}
   */
  @Deprecated
  public RibbonClient() {
    this(new Client.Default(null, null));
  }

  /**
   * @deprecated Use the {@link RibbonClient#create()}
   */
  @Deprecated
  public RibbonClient(Client delegate) {
    this(delegate, new LBClientFactory.Default());
  }

  RibbonClient(Client delegate, LBClientFactory lbClientFactory) {
    this.delegate = delegate;
    this.lbClientFactory = lbClientFactory;
  }

  /**
   * TODO: 使用ribbon时，client执行请求的时候，会走到这
   * @param request safe to replay.
   * @param options options to apply to this request.
   * @return
   * @throws IOException
   */
  @Override
  public Response execute(Request request, Request.Options options) throws IOException {
    try {
      // TODO: 生成uri
      URI asUri = URI.create(request.url());
      String clientName = asUri.getHost();
      // TODO: 把host去掉了
      URI uriWithoutHost = cleanUrl(request.url(), clientName);
      // TODO: 创建ribbonRequest，最终使用LBClient去执行请求
      LBClient.RibbonRequest ribbonRequest =
          new LBClient.RibbonRequest(delegate, request, uriWithoutHost);
      // TODO: 这里 clientName 这个应该就是服务名了，负载均衡执行
      return lbClient(clientName).executeWithLoadBalancer(ribbonRequest,
          new FeignOptionsClientConfig(options)).toResponse();
    } catch (ClientException e) {
      propagateFirstIOException(e);
      throw new RuntimeException(e);
    }
  }

  static void propagateFirstIOException(Throwable throwable) throws IOException {
    while (throwable != null) {
      if (throwable instanceof IOException) {
        throw (IOException) throwable;
      }
      throwable = throwable.getCause();
    }
  }

  static URI cleanUrl(String originalUrl, String host) {
    return URI.create(originalUrl.replaceFirst(host, ""));
  }

  /**
   * TODO: 根据clientName，使用factory创建出来一个LBClient
   * @param clientName
   * @return
   */
  private LBClient lbClient(String clientName) {
    return lbClientFactory.create(clientName);
  }

  static class FeignOptionsClientConfig extends DefaultClientConfigImpl {

    public FeignOptionsClientConfig(Request.Options options) {
      setProperty(CommonClientConfigKey.ConnectTimeout, options.connectTimeoutMillis());
      setProperty(CommonClientConfigKey.ReadTimeout, options.readTimeoutMillis());
      setProperty(CommonClientConfigKey.FollowRedirects, options.isFollowRedirects());
    }

    @Override
    public void loadProperties(String clientName) {

    }

    @Override
    public void loadDefaultValues() {

    }

  }

  /**
   * TODO: 采用builder模式构建RibbonClient
   */
  public static final class Builder {

    Builder() {}

    private Client delegate;
    private LBClientFactory lbClientFactory;

    public Builder delegate(Client delegate) {
      this.delegate = delegate;
      return this;
    }

    public Builder lbClientFactory(LBClientFactory lbClientFactory) {
      this.lbClientFactory = lbClientFactory;
      return this;
    }

    public RibbonClient build() {
      return new RibbonClient(
              // TODO: 如果delegate 不为空，则用你设置的，否则用默认的java自带的
          delegate != null ? delegate : new Client.Default(null, null),
          lbClientFactory != null ? lbClientFactory : new LBClientFactory.Default());
    }
  }
}
