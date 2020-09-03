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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import feign.Contract.BaseContract;

/**
 * {@link Contract} base implementation that works by declaring witch annotations should be
 * processed and how each annotation modifies {@link MethodMetadata}
 */
public abstract class DeclarativeContract extends BaseContract {

  /**
   * TODO: 注册的class支持的注解，处理器
   */
  private final List<GuardedAnnotationProcessor> classAnnotationProcessors = new ArrayList<>();
  /**
   * TODO: 注册的method支持的注解，处理器
   */
  private final List<GuardedAnnotationProcessor> methodAnnotationProcessors = new ArrayList<>();
  /**
   * TODO: 注册的参数Parameter支持的注解，处理器
   */
  private final Map<Class<Annotation>, DeclarativeContract.ParameterAnnotationProcessor<Annotation>> parameterAnnotationProcessors =
      new HashMap<>();

  @Override
  public final List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
    // any implementations must register processors
    return super.parseAndValidateMetadata(targetType);
  }

  /**
   * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target type
   * (unless they are the same).
   *
   * @param data metadata collected so far relating to the current java method.
   * @param clz the class to process
   */
  @Override
  protected final void processAnnotationOnClass(MethodMetadata data, Class<?> targetType) {
    // TODO: 把接口上的注解拿到，如果该注解满足该处理器的条件 ok 那收集起来
    final List<GuardedAnnotationProcessor> processors = Arrays.stream(targetType.getAnnotations())
        .flatMap(annotation -> classAnnotationProcessors.stream()
            .filter(processor -> processor.test(annotation)))
        .collect(Collectors.toList());

    // TODO: 如果注解处理器不为空，表示可以处理当前class上面的注解
    if (!processors.isEmpty()) {
      // TODO: 使用过滤出来的processors去处理 targetType 上面所有的注解
      Arrays.stream(targetType.getAnnotations())
          .forEach(annotation -> processors.stream()
                  // TODO: 处理符合processor条件的注解
              .filter(processor -> processor.test(annotation))
              .forEach(processor -> processor.process(annotation, data)));
    } else {
      // TODO: 那首先看看 targetClass上面是不是就没有注解
      if (targetType.getAnnotations().length == 0) {
        // TODO: 添加警告信息
        data.addWarning(String.format(
            "Class %s has no annotations, it may affect contract %s",
            targetType.getSimpleName(),
            getClass().getSimpleName()));
      } else {
        // TODO: class上面有注解，但是没有processor能够去处理，也加个警告信息
        data.addWarning(String.format(
            "Class %s has annotations %s that are not used by contract %s",
            targetType.getSimpleName(),
            Arrays.stream(targetType.getAnnotations())
                .map(annotation -> annotation.annotationType()
                    .getSimpleName())
                .collect(Collectors.toList()),
            getClass().getSimpleName()));
      }
    }
  }

  /**
   * @param data metadata collected so far relating to the current java method.
   * @param annotation annotations present on the current method annotation.
   * @param method method currently being processed.
   */
  @Override
  protected final void processAnnotationOnMethod(MethodMetadata data,
                                                 Annotation annotation,
                                                 Method method) {
    // TODO: 第一步，收集能够处理此注解的processor
    List<GuardedAnnotationProcessor> processors = methodAnnotationProcessors.stream()
        .filter(processor -> processor.test(annotation))
        .collect(Collectors.toList());

    // TODO: 如果此processor不为空，那么遍历它去处理annotation
    if (!processors.isEmpty()) {
      processors.forEach(processor -> processor.process(annotation, data));
    } else {
      // TODO: 否则，加个警告信息
      data.addWarning(String.format(
          "Method %s has an annotation %s that is not used by contract %s",
          method.getName(),
          annotation.annotationType()
              .getSimpleName(),
          getClass().getSimpleName()));
    }
  }


  /**
   * @param data metadata collected so far relating to the current java method.
   * @param annotations annotations present on the current parameter annotation., 当前参数的所有的注解
   * @param paramIndex if you find a name in {@code annotations}, call
   *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
   * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
   *         http-relevant annotation.
   */
  @Override
  protected final boolean processAnnotationsOnParameter(MethodMetadata data,
                                                        Annotation[] annotations,
                                                        int paramIndex) {
    List<Annotation> matchingAnnotations = Arrays.stream(annotations)
        .filter(
                // TODO: 如果参数注解解析器中 包含这个注解，那就把它收集起来吧
            annotation -> parameterAnnotationProcessors.containsKey(annotation.annotationType()))
        .collect(Collectors.toList());
    // TODO: 如果不为空，就开始去处理
    if (!matchingAnnotations.isEmpty()) {
      // TODO: 调用该注解 需要的 参数注解解析器
      matchingAnnotations.forEach(annotation -> parameterAnnotationProcessors
          .getOrDefault(annotation.annotationType(), ParameterAnnotationProcessor.DO_NOTHING)
              // TODO: 解析完，信息会放到data中
          .process(annotation, data, paramIndex));

    } else {
      final Parameter parameter = data.method().getParameters()[paramIndex];
      // TODO: 下面主要就是存储了一些警告信息，如果参数名有，则拿到，否则取参数类型的最简名称
      String parameterName = parameter.isNamePresent()
          ? parameter.getName()
          : parameter.getType().getSimpleName();
      // TODO: 注解个数为0，添加这个信息
      if (annotations.length == 0) {
        data.addWarning(String.format(
            "Parameter %s has no annotations, it may affect contract %s",
            parameterName,
            getClass().getSimpleName()));
      } else {
        // TODO: 或者你这个参数 有注解，但是我contract 一个都处理不了
        data.addWarning(String.format(
            "Parameter %s has annotations %s that are not used by contract %s",
            parameterName,
            Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType()
                    .getSimpleName())
                .collect(Collectors.toList()),
            getClass().getSimpleName()));
      }
    }
    return false;
  }


  /* ---------------- 由子类决定去注册class method parameter支持处理哪些注解，然后父类进行处理啊 -------------- */

  /**
   * Called while class annotations are being processed
   *
   * @param annotationType to be processed
   * @param processor function that defines the annotations modifies {@link MethodMetadata}
   */
  protected <E extends Annotation> void registerClassAnnotation(Class<E> annotationType,
                                                                DeclarativeContract.AnnotationProcessor<E> processor) {
    registerClassAnnotation(
        annotation -> annotation.annotationType().equals(annotationType),
        processor);
  }

  /**
   * Called while class annotations are being processed
   *
   * @param predicate to check if the annotation should be processed or not
   * @param processor function that defines the annotations modifies {@link MethodMetadata}
   */
  protected <E extends Annotation> void registerClassAnnotation(Predicate<E> predicate,
                                                                DeclarativeContract.AnnotationProcessor<E> processor) {
    this.classAnnotationProcessors.add(new GuardedAnnotationProcessor(predicate, processor));
  }

  /**
   * Called while method annotations are being processed
   *
   * @param annotationType to be processed
   * @param processor function that defines the annotations modifies {@link MethodMetadata}
   */
  protected <E extends Annotation> void registerMethodAnnotation(Class<E> annotationType,
                                                                 DeclarativeContract.AnnotationProcessor<E> processor) {
    registerMethodAnnotation(
            // TODO: 条件是，你的annotation 必须要和我这里注册的annotationType一样，我才会去处理
        annotation -> annotation.annotationType().equals(annotationType),
        processor);
  }

  /**
   * Called while method annotations are being processed
   *
   * @param predicate to check if the annotation should be processed or not
   * @param processor function that defines the annotations modifies {@link MethodMetadata}
   */
  protected <E extends Annotation> void registerMethodAnnotation(Predicate<E> predicate,
                                                                 DeclarativeContract.AnnotationProcessor<E> processor) {
    this.methodAnnotationProcessors.add(new GuardedAnnotationProcessor(predicate, processor));
  }

  /**
   * Called while method parameter annotations are being processed
   *
   * @param annotation to be processed
   * @param processor function that defines the annotations modifies {@link MethodMetadata}
   */
  protected <E extends Annotation> void registerParameterAnnotation(Class<E> annotation,
                                                                    DeclarativeContract.ParameterAnnotationProcessor<E> processor) {
    this.parameterAnnotationProcessors.put((Class) annotation,
        (DeclarativeContract.ParameterAnnotationProcessor) processor);
  }

  /* ---------------- 上面为注册方法，用于注册注解处理器 -------------- */

  @FunctionalInterface
  public interface AnnotationProcessor<E extends Annotation> {

    /**
     * @param annotation present on the current element.
     * @param metadata collected so far relating to the current java method.
     */
    void process(E annotation, MethodMetadata metadata);
  }

  @FunctionalInterface
  public interface ParameterAnnotationProcessor<E extends Annotation> {

    DeclarativeContract.ParameterAnnotationProcessor<Annotation> DO_NOTHING = (ann, data, i) -> {
    };

    /**
     * @param annotation present on the current parameter annotation.
     * @param metadata metadata collected so far relating to the current java method.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    void process(E annotation, MethodMetadata metadata, int paramIndex);
  }

  /**
   *  TODO: AnnotationProcessor的 一个实现，组合实现了Predicate 可以进行条件判断
   */
  private class GuardedAnnotationProcessor
      implements Predicate<Annotation>, DeclarativeContract.AnnotationProcessor<Annotation> {

    private final Predicate<Annotation> predicate;
    private final DeclarativeContract.AnnotationProcessor<Annotation> processor;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private GuardedAnnotationProcessor(Predicate predicate,
        DeclarativeContract.AnnotationProcessor processor) {
      this.predicate = predicate;
      this.processor = processor;
    }

    @Override
    public void process(Annotation annotation, MethodMetadata metadata) {
      processor.process(annotation, metadata);
    }

    @Override
    public boolean test(Annotation t) {
      return predicate.test(t);
    }

  }

}
