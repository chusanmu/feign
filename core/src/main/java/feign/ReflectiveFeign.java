/**
 * Copyright 2012-2020 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.*;
import feign.template.UriUtils;

public class ReflectiveFeign extends Feign {

    /**
     * 提供方法，给接口每个方法生成一个处理器
     */
    private final ParseHandlersByName targetToHandlersByName;
    /**
     * 它是调度中心
     */
    private final InvocationHandlerFactory factory;
    private final QueryMapEncoder queryMapEncoder;

    ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
                    QueryMapEncoder queryMapEncoder) {
        this.targetToHandlersByName = targetToHandlersByName;
        this.factory = factory;
        this.queryMapEncoder = queryMapEncoder;
    }

    /**
     * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
     * to cache the result.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T newInstance(Target<T> target) {
        // TODO: 拿到该接口所有方法对应的处理器Map
        Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
        // TODO: 真要处理调用的Method对应的处理器Map
        Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
        // TODO: 对接口默认方法作为处理方法提供支持，不用发http请求
        List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

        // TODO: 查找该接口所有的Method
        for (Method method : target.type().getMethods()) {
            // TODO: 如果是Object的方法，直接continue
            if (method.getDeclaringClass() == Object.class) {
                continue;
            } else if (Util.isDefault(method)) {
                // TODO: 如果是Default默认方法，那该方法就用DefaultMethodHandler去处理
                DefaultMethodHandler handler = new DefaultMethodHandler(method);
                defaultMethodHandlers.add(handler);
                methodToHandler.put(method, handler);
            } else {
                // TODO: 否则就nameToHandler.get(Feign.configKey(target.type(), method))用它去处理
                methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
            }
        }
        // TODO: 为该目标接口类型创建一个InvocationHandler, 它持有methodToHandler这个Map, 负责全局调度所有的Method方法，并且为此接口创建一个代理对象
        InvocationHandler handler = factory.create(target, methodToHandler);
        T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
                new Class<?>[]{target.type()}, handler);
        // TODO: 进行bind绑定
        for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
            defaultMethodHandler.bindTo(proxy);
        }
        return proxy;
    }

    static class FeignInvocationHandler implements InvocationHandler {

        private final Target target;
        private final Map<Method, MethodHandler> dispatch;

        FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
            this.target = checkNotNull(target, "target");
            this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("equals".equals(method.getName())) {
                try {
                    Object otherHandler =
                            args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
                    return equals(otherHandler);
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else if ("hashCode".equals(method.getName())) {
                return hashCode();
            } else if ("toString".equals(method.getName())) {
                return toString();
            }
            // TODO: 默认委托给了 SynchronousMethodHandler 去执行，发送http请求，或者调用接口本地方法
            return dispatch.get(method).invoke(args);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof FeignInvocationHandler) {
                FeignInvocationHandler other = (FeignInvocationHandler) obj;
                return target.equals(other.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return target.hashCode();
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }

    /**
     * TODO: 将feign.Target转为Map<String,MethodHandler>也就是为每一个configKey，找到一个处理它的MethodHandler
     * 为指定接口类型的每个方法生成其对应的MethodHandler处理器
     */
    static final class ParseHandlersByName {

        /**
         * 提取器
         */
        private final Contract contract;
        private final Options options;
        private final Encoder encoder;
        private final Decoder decoder;
        private final ErrorDecoder errorDecoder;
        private final QueryMapEncoder queryMapEncoder;
        private final SynchronousMethodHandler.Factory factory;

        ParseHandlersByName(
                Contract contract,
                Options options,
                Encoder encoder,
                Decoder decoder,
                QueryMapEncoder queryMapEncoder,
                ErrorDecoder errorDecoder,
                SynchronousMethodHandler.Factory factory) {
            this.contract = contract;
            this.options = options;
            this.factory = factory;
            this.errorDecoder = errorDecoder;
            this.queryMapEncoder = queryMapEncoder;
            this.encoder = checkNotNull(encoder, "encoder");
            this.decoder = checkNotNull(decoder, "decoder");
        }

        public Map<String, MethodHandler> apply(Target target) {
            // TODO: 通过contract提取出该类所有方法的元数据信息，MethodMetadata, 它会解析注解，不同的实现支持的注解是不一样的
            List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
            // TODO: 一个方法一个方法的处理，生成器对应的MethodHandler处理器
            Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
            for (MethodMetadata md : metadata) {
                BuildTemplateByResolvingArgs buildTemplate;
                // TODO: 针对不同元数据参数，调用不同的RequestTemplate.Factory实现类完成处理
                if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
                    // TODO: 若存在表单参数formParams, 并且没有body模板，就执行表单形式的构建
                    buildTemplate =
                            new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
                } else if (md.bodyIndex() != null) {
                    // TODO: 存在body的话 就是body
                    buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
                } else {
                    // TODO: 否则就是普通形式，查询参数构建方式
                    buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
                }
                if (md.isIgnored()) {
                    result.put(md.configKey(), args -> {
                        throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
                    });
                } else {
                    // TODO: 通过factory.create创建出MethodHandler实例，缓存结果
                    result.put(md.configKey(),
                            factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
                }
            }
            return result;
        }
    }

    private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

        private final QueryMapEncoder queryMapEncoder;

        protected final MethodMetadata metadata;
        protected final Target<?> target;
        private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

        private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder,
                                             Target target) {
            this.metadata = metadata;
            this.target = target;
            this.queryMapEncoder = queryMapEncoder;
            if (metadata.indexToExpander() != null) {
                indexToExpander.putAll(metadata.indexToExpander());
                return;
            }
            if (metadata.indexToExpanderClass().isEmpty()) {
                return;
            }
            for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
                    .indexToExpanderClass().entrySet()) {
                try {
                    indexToExpander
                            .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
                } catch (InstantiationException e) {
                    throw new IllegalStateException(e);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public RequestTemplate create(Object[] argv) {
            // TODO: 先拷贝出来一份RequestTemplate出来，但这并不是最终要return的
            RequestTemplate mutable = RequestTemplate.from(metadata.template());
            mutable.feignTarget(target);
            if (metadata.urlIndex() != null) {
                int urlIndex = metadata.urlIndex();
                checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
                mutable.target(String.valueOf(argv[urlIndex]));
            }
            // TODO: varBuilder装载所有的k-v
            Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
            for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
                int i = entry.getKey();
                Object value = argv[entry.getKey()];
                if (value != null) { // Null values are skipped.
                    if (indexToExpander.containsKey(i)) {
                        value = expandElements(indexToExpander.get(i), value);
                    }
                    for (String name : entry.getValue()) {
                        varBuilder.put(name, value);
                    }
                }
            }
            // TODO: 调用RequestTemplate#resolve()方法，得到一个全新的实例
            RequestTemplate template = resolve(argv, mutable, varBuilder);
            // TODO: 支持queryMap
            if (metadata.queryMapIndex() != null) {
                // add query map parameters after initial resolve so that they take
                // precedence over any predefined values
                Object value = argv[metadata.queryMapIndex()];
                Map<String, Object> queryMap = toQueryMap(value);
                template = addQueryMapQueryParameters(queryMap, template);
            }
            // TODO: 支持headerMap
            if (metadata.headerMapIndex() != null) {
                template =
                        addHeaderMapHeaders((Map<String, Object>) argv[metadata.headerMapIndex()], template);
            }

            return template;
        }

        private Map<String, Object> toQueryMap(Object value) {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            try {
                return queryMapEncoder.encode(value);
            } catch (EncodeException e) {
                throw new IllegalStateException(e);
            }
        }

        private Object expandElements(Expander expander, Object value) {
            if (value instanceof Iterable) {
                return expandIterable(expander, (Iterable) value);
            }
            return expander.expand(value);
        }

        private List<String> expandIterable(Expander expander, Iterable value) {
            List<String> values = new ArrayList<String>();
            for (Object element : value) {
                if (element != null) {
                    values.add(expander.expand(element));
                }
            }
            return values;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                    RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : headerMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                Object currValue = currEntry.getValue();
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null : nextObject.toString());
                    }
                } else {
                    values.add(currValue == null ? null : currValue.toString());
                }

                mutable.header(currEntry.getKey(), values);
            }
            return mutable;
        }

        @SuppressWarnings("unchecked")
        private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                           RequestTemplate mutable) {
            for (Entry<String, Object> currEntry : queryMap.entrySet()) {
                Collection<String> values = new ArrayList<String>();

                boolean encoded = metadata.queryMapEncoded();
                Object currValue = currEntry.getValue();
                if (currValue instanceof Iterable<?>) {
                    Iterator<?> iter = ((Iterable<?>) currValue).iterator();
                    while (iter.hasNext()) {
                        Object nextObject = iter.next();
                        values.add(nextObject == null ? null
                                : encoded ? nextObject.toString()
                                : UriUtils.encode(nextObject.toString()));
                    }
                } else if (currValue instanceof Object[]) {
                    for (Object value : (Object[]) currValue) {
                        values.add(value == null ? null
                                : encoded ? value.toString() : UriUtils.encode(value.toString()));
                    }
                } else {
                    values.add(currValue == null ? null
                            : encoded ? currValue.toString() : UriUtils.encode(currValue.toString()));
                }

                mutable.query(encoded ? currEntry.getKey() : UriUtils.encode(currEntry.getKey()), values);
            }
            return mutable;
        }

        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            return mutable.resolve(variables);
        }
    }

    /**
     *
     */
    private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                                 QueryMapEncoder queryMapEncoder, Target target) {
            super(metadata, queryMapEncoder, target);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
            for (Entry<String, Object> entry : variables.entrySet()) {
                if (metadata.formParams().contains(entry.getKey())) {
                    formVariables.put(entry.getKey(), entry.getValue());
                }
            }
            try {
                encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }

    /**
     *  TODO  ：加入了解码器 feign.codec.Encoder，因此对于方法体Body的参数，它可以先让解码器解码后，放进body里，在执行父类的解析逻辑
     */
    private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

        private final Encoder encoder;

        private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
                                             QueryMapEncoder queryMapEncoder, Target target) {
            super(metadata, queryMapEncoder, target);
            this.encoder = encoder;
        }

        @Override
        protected RequestTemplate resolve(Object[] argv,
                                          RequestTemplate mutable,
                                          Map<String, Object> variables) {
            Object body = argv[metadata.bodyIndex()];
            checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
            try {
                encoder.encode(body, metadata.bodyType(), mutable);
            } catch (EncodeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EncodeException(e.getMessage(), e);
            }
            return super.resolve(argv, mutable, variables);
        }
    }
}
