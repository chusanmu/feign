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

import static feign.Util.CONTENT_ENCODING;
import static feign.Util.CONTENT_LENGTH;
import static feign.Util.ENCODING_DEFLATE;
import static feign.Util.ENCODING_GZIP;
import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import static feign.Util.isBlank;
import static feign.Util.isNotBlank;
import static java.lang.String.format;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import feign.Request.Options;

/**
 * Submits HTTP {@link Request requests}. Implementations are expected to be thread-safe. TODO:
 * 用来发送请求，Client, Feign设计的很有特点，默认高内聚，实现类 在接口中
 */
public interface Client {

  /**
   * Executes a request against its {@link Request#url() url} and returns a response.
   *
   * @param request safe to replay.
   * @param options options to apply to this request.
   * @return connected response, {@link Response.Body} is absent or unread.
   * @throws IOException on a network error connecting to {@link Request#url()}.
   */
  Response execute(Request request, Options options) throws IOException;

  /**
   * TODO: 默认使用JDK的HttpURLConnection 来发送请求， JDK默认的HttpURLConnection 会把GET请求转为POST请求，需要注意
   */
  class Default implements Client {

    private final SSLSocketFactory sslContextFactory;
    private final HostnameVerifier hostnameVerifier;

    /**
     * Disable the request body internal buffering for {@code HttpURLConnection}.
     * 
     * @see HttpURLConnection#setFixedLengthStreamingMode(int)
     * @see HttpURLConnection#setFixedLengthStreamingMode(long)
     * @see HttpURLConnection#setChunkedStreamingMode(int)
     */
    private final boolean disableRequestBuffering;

    /**
     * Create a new client, which disable request buffering by default.
     * 
     * @param sslContextFactory SSLSocketFactory for secure https URL connections.
     * @param hostnameVerifier the host name verifier.
     */
    public Default(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier) {
      this.sslContextFactory = sslContextFactory;
      this.hostnameVerifier = hostnameVerifier;
      this.disableRequestBuffering = true;
    }

    /**
     * Create a new client.
     * 
     * @param sslContextFactory SSLSocketFactory for secure https URL connections.
     * @param hostnameVerifier the host name verifier.
     * @param disableRequestBuffering Disable the request body internal buffering for
     *        {@code HttpURLConnection}.
     */
    public Default(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier,
        boolean disableRequestBuffering) {
      super();
      this.sslContextFactory = sslContextFactory;
      this.hostnameVerifier = hostnameVerifier;
      this.disableRequestBuffering = disableRequestBuffering;
    }

    @Override
    public Response execute(Request request, Options options) throws IOException {
      // TODO: 通过Request 构建一个HttpURLConnection
      HttpURLConnection connection = convertAndSend(request, options);
      // TODO: 得到response 之后进行解析
      return convertResponse(connection, request);
    }

    Response convertResponse(HttpURLConnection connection, Request request) throws IOException {
      int status = connection.getResponseCode();
      String reason = connection.getResponseMessage();

      if (status < 0) {
        throw new IOException(format("Invalid status(%s) executing %s %s", status,
            connection.getRequestMethod(), connection.getURL()));
      }

      Map<String, Collection<String>> headers = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
        // response message
        if (field.getKey() != null) {
          headers.put(field.getKey(), field.getValue());
        }
      }

      Integer length = connection.getContentLength();
      if (length == -1) {
        length = null;
      }
      InputStream stream;
      if (status >= 400) {
        stream = connection.getErrorStream();
      } else {
        stream = connection.getInputStream();
      }
      return Response.builder()
          .status(status)
          .reason(reason)
          .headers(headers)
          .request(request)
          .body(stream, length)
          .build();
    }

    public HttpURLConnection getConnection(final URL url) throws IOException {
      return (HttpURLConnection) url.openConnection();
    }

    HttpURLConnection convertAndSend(Request request, Options options) throws IOException {
      // TODO: 创建一个url
      final URL url = new URL(request.url());
      // TODO: 原生HttpClient,就是使用了java里面的 HttpURLConnection
      final HttpURLConnection connection = this.getConnection(url);
      if (connection instanceof HttpsURLConnection) {
        HttpsURLConnection sslCon = (HttpsURLConnection) connection;
        if (sslContextFactory != null) {
          sslCon.setSSLSocketFactory(sslContextFactory);
        }
        if (hostnameVerifier != null) {
          sslCon.setHostnameVerifier(hostnameVerifier);
        }
      }
      // TODO: 设置连接超时时间
      connection.setConnectTimeout(options.connectTimeoutMillis());
      // TODO: 设置请求超时时间
      connection.setReadTimeout(options.readTimeoutMillis());
      connection.setAllowUserInteraction(false);
      connection.setInstanceFollowRedirects(options.isFollowRedirects());
      // TODO: 设置请求方式，get post put
      connection.setRequestMethod(request.httpMethod().name());

      // TODO: 设置请求头，Content_Encoding
      Collection<String> contentEncodingValues = request.headers().get(CONTENT_ENCODING);
      boolean gzipEncodedRequest =
          contentEncodingValues != null && contentEncodingValues.contains(ENCODING_GZIP);
      boolean deflateEncodedRequest =
          contentEncodingValues != null && contentEncodingValues.contains(ENCODING_DEFLATE);

      boolean hasAcceptHeader = false;
      Integer contentLength = null;
      // TODO: 看看请求头里面有没有Accept字段
      for (String field : request.headers().keySet()) {
        if (field.equalsIgnoreCase("Accept")) {
          hasAcceptHeader = true;
        }
        for (String value : request.headers().get(field)) {
          // TODO: 看有没有content_length字段
          if (field.equals(CONTENT_LENGTH)) {
            if (!gzipEncodedRequest && !deflateEncodedRequest) {
              // TODO: 设置content_length
              contentLength = Integer.valueOf(value);
              connection.addRequestProperty(field, value);
            }
          } else {
            // TODO: 设置请求头
            connection.addRequestProperty(field, value);
          }
        }
      }
      // Some servers choke on the default accept string.
      // TODO: 如果你没设置accept，那默认就全接受了
      if (!hasAcceptHeader) {
        connection.addRequestProperty("Accept", "*/*");
      }
      // TODO: 如果body体不为空
      if (request.body() != null) {
        if (disableRequestBuffering) {
          if (contentLength != null) {
            // TODO: 存在contentLength的情况
            connection.setFixedLengthStreamingMode(contentLength);
          } else {
            // TODO: 设置缓存块
            connection.setChunkedStreamingMode(8196);
          }
        }
        connection.setDoOutput(true);
        // TODO: 获得输出流，开始写出去
        OutputStream out = connection.getOutputStream();
        // TODO: 如果开启了gzip压缩
        if (gzipEncodedRequest) {
          // TODO: 使用gzip包装流
          out = new GZIPOutputStream(out);
        } else if (deflateEncodedRequest) {
          out = new DeflaterOutputStream(out);
        }
        try {
          // TODO: 最后写出流，把body体写出
          out.write(request.body());
        } finally {
          try {
            // TODO: 关闭输出流
            out.close();
          } catch (IOException suppressed) { // NOPMD
          }
        }
      }
      // TODO: 最后把连接返回回去，一个请求就完成了
      return connection;
    }
  }

  /**
   * Client that supports a {@link java.net.Proxy}.
   */
  class Proxied extends Default {

    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    private final Proxy proxy;
    private String credentials;

    public Proxied(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier,
        Proxy proxy) {
      super(sslContextFactory, hostnameVerifier);
      checkNotNull(proxy, "a proxy is required.");
      this.proxy = proxy;
    }

    public Proxied(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier,
        Proxy proxy, String proxyUser, String proxyPassword) {
      this(sslContextFactory, hostnameVerifier, proxy);
      checkArgument(isNotBlank(proxyUser), "proxy user is required.");
      checkArgument(isNotBlank(proxyPassword), "proxy password is required.");
      this.credentials = basic(proxyUser, proxyPassword);
    }

    @Override
    public HttpURLConnection getConnection(URL url) throws IOException {
      HttpURLConnection connection = (HttpURLConnection) url.openConnection(this.proxy);
      if (isNotBlank(this.credentials)) {
        connection.addRequestProperty(PROXY_AUTHORIZATION, this.credentials);
      }
      return connection;
    }

    public String getCredentials() {
      return this.credentials;
    }

    private String basic(String username, String password) {
      String token = username + ":" + password;
      byte[] bytes = token.getBytes(StandardCharsets.ISO_8859_1);
      String encoded = Base64.getEncoder().encodeToString(bytes);
      return "Basic " + encoded;
    }
  }
}
