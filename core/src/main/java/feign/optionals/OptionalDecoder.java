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
package feign.optionals;

import feign.Response;
import feign.Util;
import feign.codec.Decoder;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

/**
 * TODO: 对返回值是Optional的 进行解码
 * todo: 对java8 的Optional进行了支持
 */
public final class OptionalDecoder implements Decoder {
  final Decoder delegate;

  public OptionalDecoder(Decoder delegate) {
    Objects.requireNonNull(delegate, "Decoder must not be null. ");
    this.delegate = delegate;
  }

  @Override
  public Object decode(Response response, Type type) throws IOException {
    // TODO: 如果不是Optional，那就直接使用delegate进行解码
    if (!isOptional(type)) {
      return delegate.decode(response, type);
    }
    // TODO: 看看响应是否是404, 204，如果是，那就是没找到啊，直接创建了个空返回
    if (response.status() == 404 || response.status() == 204) {
      return Optional.empty();
    }
    // TODO: 把泛型类型解析出来
    Type enclosedType = Util.resolveLastTypeParameter(type, Optional.class);
    // TODO: 然后包到了Optional中
    return Optional.ofNullable(delegate.decode(response, enclosedType));
  }

  static boolean isOptional(Type type) {
    // TODO: 如果它不带泛型，那肯定就不是Optional嘛，Optional<>是这种的
    if (!(type instanceof ParameterizedType)) {
      return false;
    }
    // TODO: 下面就是判断是否是Optional了
    ParameterizedType parameterizedType = (ParameterizedType) type;
    return parameterizedType.getRawType().equals(Optional.class);
  }
}
