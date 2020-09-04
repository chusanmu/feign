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
package feign.spring;

import java.util.ArrayList;
import java.util.Collection;
import org.springframework.web.bind.annotation.*;
import feign.DeclarativeContract;
import feign.MethodMetadata;
import feign.Request;

/**
 * TODO: 处理spring系列的注解啊,对Spring的支持，注意，在spring-cloud-feign中没有使用这个类，而是使用的SpringmvcContract, 如果使用这个类，需要导入feign-spring
 */
public class SpringContract extends DeclarativeContract {

  static final String ACCEPT = "Accept";
  static final String CONTENT_TYPE = "Content-Type";

  /**
   * TODO: 构造函数中向父类注册 注解及其注解处理器
   */
  public SpringContract() {

    /* ---------------- 常用的Mapping注解 -------------- */

    // TODO: 对类上的RequestMapping进行处理，会处理 uri, 请求方法，produces consumes等值
    registerClassAnnotation(RequestMapping.class, (requestMapping, data) -> {
      // TODO: 调用此方法，主要是对uri进行设置
      appendMappings(data, requestMapping.value());
      // TODO: 设置请求方式
      if (requestMapping.method().length == 1)
        data.template().method(Request.HttpMethod.valueOf(requestMapping.method()[0].name()));
      // TODO: 设置produces和consumes，主要设置了Accept和Consumes
      handleProducesAnnotation(data, requestMapping.produces());
      handleConsumesAnnotation(data, requestMapping.consumes());
    });

    // TODO: 对方法上的RequestMapping进行设置，会处理uri, 请求方法等，注意，这里没有处理products consumes等值
    registerMethodAnnotation(RequestMapping.class, (requestMapping, data) -> {
      // TODO: 从requestMapping中把value取出来
      String[] mappings = requestMapping.value();
      // TODO: 同样调用appendMappings方法
      appendMappings(data, mappings);
      // TODO:
      if (requestMapping.method().length == 1)
        data.template().method(Request.HttpMethod.valueOf(requestMapping.method()[0].name()));
    });

    /* ---------------- 下面处理 GetMapping, PostMapping , PutMapping等等 -------------- */

    registerMethodAnnotation(GetMapping.class, (mapping, data) -> {
      appendMappings(data, mapping.value());
      data.template().method(Request.HttpMethod.GET);
      handleProducesAnnotation(data, mapping.produces());
      handleConsumesAnnotation(data, mapping.consumes());
    });

    registerMethodAnnotation(PostMapping.class, (mapping, data) -> {
      appendMappings(data, mapping.value());
      data.template().method(Request.HttpMethod.POST);
      handleProducesAnnotation(data, mapping.produces());
      handleConsumesAnnotation(data, mapping.consumes());
    });

    registerMethodAnnotation(PutMapping.class, (mapping, data) -> {
      appendMappings(data, mapping.value());
      data.template().method(Request.HttpMethod.PUT);
      handleProducesAnnotation(data, mapping.produces());
      handleConsumesAnnotation(data, mapping.consumes());
    });

    registerMethodAnnotation(DeleteMapping.class, (mapping, data) -> {
      appendMappings(data, mapping.value());
      data.template().method(Request.HttpMethod.DELETE);
      handleProducesAnnotation(data, mapping.produces());
      handleConsumesAnnotation(data, mapping.consumes());
    });

    registerMethodAnnotation(PatchMapping.class, (mapping, data) -> {
      appendMappings(data, mapping.value());
      data.template().method(Request.HttpMethod.PATCH);
      handleProducesAnnotation(data, mapping.produces());
      handleConsumesAnnotation(data, mapping.consumes());
    });

    // TODO: 对ResponseBody进行处理，这里很简单，其实就是设置了consumes类型为application/json类型，Content-Type类型设置为application/json类型
    registerMethodAnnotation(ResponseBody.class, (body, data) -> {
      handleConsumesAnnotation(data, "application/json");
    });
    // TODO: 还支持ExceptionHandler注解，表示忽略此方法
    registerMethodAnnotation(ExceptionHandler.class, (ann, data) -> {
      data.ignoreMethod();
    });
    // TODO: 添加PathVariable注解支持，这个注解相当于feign注解中的 @Param注解，之后会进行参数填充
    registerParameterAnnotation(PathVariable.class, (parameterAnnotation, data, paramIndex) -> {
      // TODO: 把PathVariable里面的值取出来
      String name = PathVariable.class.cast(parameterAnnotation).value();
      // TODO: 不管是pathVariable里面的变量 还是RequestParam里面的变量，最后都会放到一个地方，所以注意不要重名
      nameParam(data, name, paramIndex);
    });
    // TODO: 注册RequestBody注解，添加了一个accept的请求头，然后设置的默认格式是json格式
    registerParameterAnnotation(RequestBody.class, (body, data, paramIndex) -> {
      handleProducesAnnotation(data, "application/json");
    });
    // TODO: 注册RequestParam
    registerParameterAnnotation(RequestParam.class, (parameterAnnotation, data, paramIndex) -> {
      // TODO: 也是把里面的值取出来，
      String name = RequestParam.class.cast(parameterAnnotation).value();
      // TODO: 添加query参数 比如 @RequestParam("name")  -> 产生 {name}
      Collection<String> query = addTemplatedParam(data.template().queries().get(name), name);
      // TODO: query ---》 {name}, 把name对应的query放进去
      data.template().query(name, query);
      // TODO: 最后也把值存到nameParam中
      nameParam(data, name, paramIndex);
    });

  }

  private void appendMappings(MethodMetadata data, String[] mappings) {
    // TODO: 遍历每个Mapping中的uri
    for (int i = 0; i < mappings.length; i++) {
      // TODO: 把里面的值取出来
      String methodAnnotationValue = mappings[i];
      // TODO: 如果不是斜杠开头的，那就加上斜杠，并且已有的url 没有才去加上斜杠
      if (!methodAnnotationValue.startsWith("/") && !data.template().url().endsWith("/")) {
        methodAnnotationValue = "/" + methodAnnotationValue;
      }
      // TODO: 如果data.template.url结尾为/，这种情况是已有的呦斜杠，并且新加的也有斜杠，那就把斜杠再去掉吧
      if (data.template().url().endsWith("/") && methodAnnotationValue.startsWith("/")) {
        methodAnnotationValue = methodAnnotationValue.substring(1);
      }
     // TODO: 最后设置uri
      data.template().uri(data.template().url() + methodAnnotationValue);
    }
  }

  /**
   * TODO: 处理produces, 注意，这里和mvc里面可能是相反的，主要是因为站在的角度不同，spring mvc作为服务端，products表示为产生，生成，指定类型的数据，
   * TODO: 而此处将请求头设置了ACCEPT produces类型，，主要表示，我feign客户端接收这种类型的数据，而站在另一个角度，我接收了这种数据，不就是生产了这种数据吗？
   * @param data
   * @param produces
   */
  private void handleProducesAnnotation(MethodMetadata data, String... produces) {
    if (produces.length == 0)
      return;
    // TODO: 把accept头移除掉
    data.template().removeHeader(ACCEPT); // remove any previous produces
    // TODO: 这里把header头 accept重新设置，设置了produces的第一个参数
    data.template().header(ACCEPT, produces[0]);
  }

  /**
   * TODO: 这里重新设置了Content-type请求头
   * @param data
   * @param consumes
   */
  private void handleConsumesAnnotation(MethodMetadata data, String... consumes) {
    if (consumes.length == 0)
      return;
    // TODO: 把Content-type请求头移除
    data.template().removeHeader(CONTENT_TYPE); // remove any previous consumes
    // TODO: 最后进行重新设置
    data.template().header(CONTENT_TYPE, consumes[0]);
  }

  protected Collection<String> addTemplatedParam(Collection<String> possiblyNull, String name) {
    if (possiblyNull == null) {
      possiblyNull = new ArrayList<String>();
    }
    possiblyNull.add(String.format("{%s}", name));
    return possiblyNull;
  }

}
