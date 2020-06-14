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
package feign.codec;

import java.io.IOException;
import java.lang.reflect.Type;
import feign.Response;
import feign.Util;
import static java.lang.String.format;

/**
 * 处理String类型的返回值
 */
public class StringDecoder implements Decoder {

  @Override
  public Object decode(Response response, Type type) throws IOException {
    Response.Body body = response.body();
    // TODO: 如果服务端返回了null, 那就直接返回null喽
    if (body == null) {
      return null;
    }
    // TODO: 仅处理String类型，把body流转为String，指定使用UTF-8编码
    if (String.class.equals(type)) {
      return Util.toString(body.asReader(Util.UTF_8));
    }
    // TODO: 不是String类型直接报错
    throw new DecodeException(response.status(),
        format("%s is not a type supported by this decoder.", type), response.request());
  }
}
