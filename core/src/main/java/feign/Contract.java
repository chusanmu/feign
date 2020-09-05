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

import static feign.Util.checkState;
import static feign.Util.emptyToNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import feign.Request.HttpMethod;

/**
 * Defines what annotations and values are valid on interfaces. TODO: 它决定了哪些注解，可以标注在接口,
 * 接口方法上是有效地，并且提取出来有效的信息，组装为MethodMetadata元信息。
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests. TODO: MethodMetadata
   * 方法元信息，可以是returnType, 请求参数，url， 查询参数，请求体等等
   * 
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

  /**
   * 抽象基类
   */
  abstract class BaseContract implements Contract {

    /**
     * TODO: 根据接口返回 接口中对应的methodMetadata，接口中存在多个method, 当然就存在多个methodMeatadata
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @see #parseAndValidateMetadata(Class) TODO: 比较重要的方法，处理传进来的接口
     */
    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
      // TODO: 类上不能存在一个泛型变量
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
          targetType.getSimpleName());
      // TODO: 接口最多只能有一个父接口
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
          targetType.getSimpleName());
      if (targetType.getInterfaces().length == 1) {
        // TODO: 判断此接口的父接口的父接口的个数是不是0，不是0则抛出异常
        checkState(targetType.getInterfaces()[0].getInterfaces().length == 0,
            "Only single-level inheritance supported: %s",
            targetType.getSimpleName());
      }
      final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      // TODO: 对该类所有的方法进行解析，包装成一个MethodMetadata，getMethods表示本类 + 父类的Public方法，意思是把父接口的方法也拿到
      for (final Method method : targetType.getMethods()) {
        // TODO: 排除static方法，Object中的方法，以及Default方法
        if (method.getDeclaringClass() == Object.class ||
            (method.getModifiers() & Modifier.STATIC) != 0 ||
            Util.isDefault(method)) {
          continue;
        }
        // TODO: 方法到元数据的解析，解析每个合法的方法
        final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        // TODO: 看看result中是否已经存在了，不存在加到缓存里面去
        checkState(!result.containsKey(metadata.configKey()), "Overrides unsupported: %s",
            metadata.configKey());
        // TODO: 最后加到缓存里面
        result.put(metadata.configKey(), metadata);
      }
      // TODO: 返回一个快照版本
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * TODO: 解析接口中的合法的方法
     * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      // TODO: 直接创建了一个MethodMetadata
      final MethodMetadata data = new MethodMetadata();
      // TODO: 把targetType，也就是接口类型设置进去
      data.targetType(targetType);
      // TODO: 把method设置进去
      data.method(method);
      // TODO: 方法返回类型是支持泛型的
      data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
      // TODO: 使用了Feign的一个工具方法，来生成configKey, 尽量唯一
      data.configKey(Feign.configKey(targetType, method));

      if (targetType.getInterfaces().length == 1) {
        // TODO: 处理接口上的注解，并且处理了父接口，所以父接口里的注解，子接口也会生效， 交给子类去处理
        // TODO: 这里处理了父接口上的注解
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      // TODO: 处理本接口上面的注解
      processAnnotationOnClass(data, targetType);

      // TODO: 处理标注在方法上的所有的注解，若子接口override了父接口的方法，注解会以子接口的为主，然后忽略父接口方法
      for (final Annotation methodAnnotation : method.getAnnotations()) {
        // TODO: 接着调用处理方法上的注解，也是个抽象方法
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      if (data.isIgnored()) {
        return data;
      }
      // TODO: 处理完注解上的方法之后，理应知道了http调用方法, POST or GET, DELETE等等
      // TODO: 如果还不知道 方法以GET调用还是POST调用，好吧，抛出异常
      checkState(data.template().method() != null,
          "Method %s not annotated with HTTP method type (ex. GET, POST)%s",
          data.configKey(), data.warnings());
      // TODO: 方法参数，支持泛型类型
      final Class<?>[] parameterTypes = method.getParameterTypes();
      final Type[] genericParameterTypes = method.getGenericParameterTypes();
      // TODO: 注解是个二维数组
      final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      final int count = parameterAnnotations.length;
      // TODO: 一个个的去处理
      for (int i = 0; i < count; i++) {
        boolean isHttpAnnotation = false;
        // TODO: 如果不为空，那就去处理
        if (parameterAnnotations[i] != null) {
          // TODO: 一样的交给子类去处理
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }

        if (isHttpAnnotation) {
          data.ignoreParamater(i);
        }
        // TODO: 如果参数类型是URI类型的，那url就以它为准，并不使用全局的了，也就是说参数以你传进来的为准了
        if (parameterTypes[i] == URI.class) {
          data.urlIndex(i);
        } else if (!isHttpAnnotation && parameterTypes[i] != Request.Options.class) {
          // TODO: 如果这个入参被解析过了
          if (data.isAlreadyProcessed(i)) {
            // TODO: 如果formParams不为空，并且bodyIndex不为空，抛异常 body类型的入参 不能被form入参使用
            // TODO: 对于feign而言你使用 @Body 来用于表单提交，是不可以的
            checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                "Body parameters cannot be used with form parameters.%s", data.warnings());
          } else {
            // TODO: 一般会走到这里，表示 没有任何注解修饰这个入参，或者有注解，但是没被处理，那么会被当成body去处理
            checkState(data.formParams().isEmpty(),
                "Body parameters cannot be used with form parameters.%s", data.warnings());
            checkState(data.bodyIndex() == null,
                "Method has too many Body parameters: %s%s", method, data.warnings());
            // TODO: 参数角标，参数类型
            data.bodyIndex(i);
            // TODO: 设置body参数类型
            data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
          }
        }
      }

      /* ---------------- 如果你在参数上查找注解 ，找到了 @HeaderMap 或者 @QueryMap， 则会进行检查参数类型 -------------- */

      if (data.headerMapIndex() != null) {
        // TODO: 去检查对应的参数类型是否是Map，并且key是否是String类型的，如果不是，ok，则抛出异常
        checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()],
            genericParameterTypes[data.headerMapIndex()]);
      }

      if (data.queryMapIndex() != null) {
        // TODO: 这里和上面不同的是，去检查入参是否是Map类型，如果是Map类型，则去检查Map的key，否则啥事都不做，而上面的，如果不是Map会抛错的
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }
      // TODO: 最后把data返回
      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
          "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        final Type[] interfaces = ((Class) genericType).getGenericInterfaces();
        if (interfaces != null) {
          for (final Type extended : interfaces) {
            if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
              // use the first extended interface we find.
              final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
              keyClass = (Class<?>) parameterTypes[0];
              break;
            }
          }
        }
      }

      if (keyClass != null) {
        checkState(String.class.equals(keyClass),
            "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }


    /* ---------------- 三个抽象方法，处理类，处理方法，处理入参，留给子类去实现 -------------- */

    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      // TODO: 设置Param注解相关值
      final Collection<String> names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }


  /**
   * TODO: 内置的唯一实现类，也是Feign的默认实现，专门用于处理注解
   */
  class Default extends DeclarativeContract {

    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

    /**
     * TODO: 它主要做的就是，向父类注册 注解处理器(解析器)
     */
    public Default() {
      // TODO: 类 支持注解@Headers，默认只注册了一个Headers注解, 默认类上只支持Headers注解，解析器，就是后面的Lambada
      super.registerClassAnnotation(Headers.class, (header, data) -> {
        // TODO: 把它的value取出来
        final String[] headersOnType = header.value();
        // TODO: 检查 headersOnType 是否有值
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            data.configKey());
        // TODO: 把Headers里面的值取出来，转换为Map
        final Map<String, Collection<String>> headers = toMap(headersOnType);
        // TODO: 把 原有的 data.template().headers 放进 headers 中
        headers.putAll(data.template().headers());
        // TODO: 然后把原有的清空，重新加进去
        data.template().headers(null); // to clear
        // TODO: 这样headers就设置好了
        data.template().headers(headers);
      });
      // TODO: 方法支持注解: @RequestLine @Body @Headers
      // TODO: 首先注册的支持 @RequestLine
      super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
        // TODO: 把 requestLine中的value取出来
        final String requestLine = ann.value();
        // TODO: 看看是不是为空，为空就抛出异常
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", data.configKey());

        // TODO: 这里用了正则表达式去校验，你requestLine中写的value 必须要满足这个正则的条件，例如 @RequestLine("GET /user/id") 这样是符合条件的
        final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        // TODO: 如果没找到，你写的value不符合条件，则抛出异常
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              data.configKey()));
        } else {
          // TODO: 然后把找到的信息加进去，第1个是 请求方式 例如 GET
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          // TODO: 然后第二个是 uri, 例如 /user/id
          data.template().uri(requestLineMatcher.group(2));
        }
        // TODO: 是否进行转义，默认是转义的
        data.template().decodeSlash(ann.decodeSlash());
        // TODO: 设置 转义格式化器
        data.template()
            .collectionFormat(ann.collectionFormat());
      });
      // TODO: 注册 Body注解，方法上同时也支持Body注解
      super.registerMethodAnnotation(Body.class, (ann, data) -> {
        // TODO: 把body注解里面的value拿到
        final String body = ann.value();
        // TODO: 校验，检查是否为空
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
            data.configKey());
        // TODO: 如果你没有用{包裹，那就直接把@Body()注解里面的值，当成body体
        if (body.indexOf('{') == -1) {
          data.template().body(body);
        } else {
          // TODO: 否则作为一个body模板放进去 ---》 存在 {
          data.template().bodyTemplate(body);
        }
      });
      // TODO: 注册headers注解
      super.registerMethodAnnotation(Headers.class, (header, data) -> {
        // TODO: 把header注解里面的value取出来，可能会有多个
        final String[] headersOnMethod = header.value();
        // TODO: 为空 就抛出异常
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            data.configKey());
        // TODO: value 转换为map，然后 加到headers中
        data.template().headers(toMap(headersOnMethod));
      });
      // TODO: 参数支持注解，@Param, @QueryMap, @HeaderMap等
      // TODO: 注册 Param注解
      super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
        // TODO: 一样把注解里面的value取出来
        final String name = paramAnnotation.value();
        checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
            paramIndex);
        // TODO: 把param name的值存到data中
        nameParam(data, name, paramIndex);
        // TODO: 把Expander 如何填充参数拿到，默认toString去填充
        final Class<? extends Param.Expander> expander = paramAnnotation.expander();
        if (expander != Param.ToStringExpander.class) {
          data.indexToExpanderClass().put(paramIndex, expander);
        }
        if (!data.template().hasRequestVariable(name)) {
          data.formParams().add(name);
        }
      });
      // TODO: 注册QueryMap注解
      super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
        // TODO: 如果queryMap已经存在了，ok那就抛出错误
        checkState(data.queryMapIndex() == null,
            "QueryMap annotation was present on multiple parameters.");
        // TODO: 设置mapIndex
        data.queryMapIndex(paramIndex);
        // TODO: 是否编码
        data.queryMapEncoded(queryMap.encoded());
      });
      // TODO: 注册headerMap
      super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
        // TODO: 如果不等于空，那就抛出错吧
        checkState(data.headerMapIndex() == null,
            "HeaderMap annotation was present on multiple parameters.");
        // TODO: 就只设置了HeadMap角标
        data.headerMapIndex(paramIndex);
      });
    }

    /**
     * TODO: 把输入，转为Map结构
     * 例如
     * @Header({"accept: cn","accept:en"})
     * 会转为 Map --》 ("accept": [cn,en])
     * @param input
     * @return
     */
    private static Map<String, Collection<String>> toMap(String[] input) {
      final Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (final String header : input) {
        final int colon = header.indexOf(':');
        final String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }

  }
}
