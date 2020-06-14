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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Controls reflective method dispatch. TODO: 控制反射方法调度
 */
public interface InvocationHandlerFactory {

  /**
   * TODO: 为target创建一个InvocationHandler 反射调用对象
   * 
   * @param target
   * @param dispatch
   * @return
   */
  InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch);

  /**
   * Like {@link InvocationHandler#invoke(Object, java.lang.reflect.Method, Object[])}, except for a
   * single method.
   */
  interface MethodHandler {

    Object invoke(Object[] argv) throws Throwable;
  }

  static final class Default implements InvocationHandlerFactory {

    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
      // TODO: 返回一个默认的FeignInvocationHandler， 从 dispatch中 根据method，拿相应的methodHandler去执行
      return new ReflectiveFeign.FeignInvocationHandler(target, dispatch);
    }
  }
}
